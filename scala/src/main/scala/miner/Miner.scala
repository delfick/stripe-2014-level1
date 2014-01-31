package miner

import sys.process.stringSeqToProcess
import akka.actor._
import com.typesafe.config.Config
import java.nio.file.{Files, StandardOpenOption, Paths, Path}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global
import org.eclipse.jgit.{api => git}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib._
import java.security.MessageDigest
import com.typesafe.config.ConfigFactory
import scala.Some
import akka.actor.Terminated
import akka.util.Timeout
import org.eclipse.jgit.api.errors.InvalidRemoteException
import akka.dispatch.{UnboundedPriorityMailbox, PriorityGenerator}
import org.eclipse.jgit.api.errors.TransportException
import org.apache.commons.io.FileUtils

object Messages {
  case class Pause()
  case class UnPause()
  case class Ready()
  case class Die()
  case class Push(sha:ObjectId, nonce:String, parent:ObjectId)
  case class Clone()
  case class Count(amount:Int)
  case class DoUpdate()
  case class Update(new_parent: Option[String])
  case class DoneTick()
  case class UpdateTick()
  case class DoWork(md:MessageDigest, commit:CommitMaker, difficulty: String)
  case class MakeCommit(commit:CommitMaker, difficulty: String)

  trait UpdateResponse
  case class Prepare()
  case class UpdateRepo()
  case class CarryOn() extends UpdateResponse
  case class NewCommitShiz(commit:CommitMaker, difficulty:String) extends UpdateResponse
}

class LookupActor(path: String) extends Actor {
  case class DetermineIdentity()
  override def preStart() { self ! DetermineIdentity }

  var buffer = Set.empty[(Any, ActorRef)]

  def determineIdentity(): Unit = {
    context.actorSelection(path) ! Identify(path)
    context.system.scheduler.scheduleOnce(3.seconds, self, ReceiveTimeout)
  }

  def receive = identifying

  def identifying:Receive = {
    case DetermineIdentity =>
      context.actorSelection(path) ! Identify(path)

    case ActorIdentity(`path`, Some(actor)) =>
      context.watch(actor)
      context.become(active(actor))
      buffer foreach { case (msg, sndr) => self.tell(msg, sndr) }

    case ActorIdentity(`path`, None) =>
      println(s"Remote actor not available: $path")
      context.system.scheduler.scheduleOnce(3.seconds, self, DetermineIdentity)

    case msg =>
      val sndr = sender
      if (buffer.size < 100000) buffer += (msg -> sndr)
  }

  def active(actor: ActorRef): Actor.Receive = {
    case Terminated(`actor`) =>
      context.system.scheduler.scheduleOnce(3.seconds, self, DetermineIdentity)
      context.become(identifying)
    case ReceiveTimeout => // ignore
    case msg =>
      val sndr = sender
      actor.tell(msg, sndr)
  }
}

case class MinerInfo(root_dir:Path, clone_url:String, public_username:String, hash_maker:ActorRef)

case class CommitMaker(tree: ObjectId, parent: ObjectId, author: PersonIdent) {
  def newNonce():String = s"${java.util.UUID.randomUUID.toString} ${System.currentTimeMillis().toInt}"

  def makeCommit(nonce:String):CommitBuilder = {
    val commit = new CommitBuilder()
    commit.setAuthor(author)
    commit.setCommitter(author)
    commit.setTreeId(tree)
    commit.setParentId(parent)
    commit.setMessage(s"Give me Bitcoins!\nnonce:$nonce")
    commit
  }

  def makeCommitString(nonce:String): String = {
    List(
      s"tree ${tree.name}"
      , s"parent ${parent.name}"
      , s"author ${author.toExternalString}"
      , s"committer ${author.toExternalString}"
      , s""
      , s"Give me Bitcoins!\nnonce:"
    ).mkString("\n")
  }

  def makeNewSha(md:MessageDigest, nonce:String):(ObjectId, String) = {
    val sha_digest = md.clone().asInstanceOf[MessageDigest].digest(Constants.encodeASCII(nonce))
    ObjectId.fromRaw(sha_digest) -> nonce
  }
}

case class HasherMailbox(settings: ActorSystem.Settings, config: Config) extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case PoisonPill => 0
    case Messages.Pause => 1
    case _ => 2
  })

class HashMaker() extends Actor {
  import Messages._

  def receive = {
    case DoWork(md, commit, difficulty) =>

      val (sha, nonce) = commit.makeNewSha(md, commit.newNonce())

      if (sha.name < difficulty) {
        sender ! Push(sha, nonce, commit.parent)
      }

      if (sha.name.startsWith("00000")) {
        println(s"===$nonce=== ${commit.parent.name}")
        println(s"\t${sha.name}\n\t$difficulty\n\t---")
      }
    }
}

class Hasher(hash_maker:ActorRef, counter:ActorRef) extends Actor {
  import Messages._
  case class Instruct()

  var paused = false
  var instruction:Option[(MakeCommit, ActorRef)] = None

  var num = 0
  var old_parent:Option[ObjectId] = None
  var md: MessageDigest = Constants.newMessageDigest

  def receive = {
    case Terminated(child) =>
      context.stop(self)

    case msg:MakeCommit =>
      if (instruction.isEmpty || instruction.get._1.commit.parent != msg.commit.parent) paused = false

      md = Constants.newMessageDigest
      val commit_base = msg.commit.makeCommitString("")
      val example_nonce = msg.commit.newNonce()
      val length = commit_base.length + example_nonce.length
      md.update(Constants.encodeASCII(s"commit $length\0$commit_base"))

      println(s"New commit base!!! ~~~$commit_base~~~")

      instruction = Some(msg -> sender)
      self ! Instruct

    case Instruct =>
      instruction map { case (MakeCommit(commit, difficulty), sndr) =>
        num += 1
        if (num % 1000000 == 0) {
          counter ! Count(num)
          num = 0
        }
        hash_maker.tell(DoWork(md, commit, difficulty), sndr)
      }
      if (!paused) self ! Instruct

    case Pause =>
      paused = true
      val paused_parent = old_parent map { _.name } getOrElse ""
      context.become({
        case UnPause =>
          context.become(receive)
        case msg:MakeCommit =>
          if (msg.commit.parent.name != paused_parent) {
            context.become(receive)
            self.tell(msg, sender)
          }
      })
  }
}

class Counter() extends Actor {
  import Messages._

  var total = 0L
  var dying = false
  var start = System.currentTimeMillis / 1000
  val first_start = start
  var last_total = 0L

  override def postStop() { doPrint(total, System.currentTimeMillis()/1000, first_start) }

  def receive = {
    case Count(amount) =>
      total += amount

      val now = System.currentTimeMillis() / 1000
      val diff = total - last_total
      val time_diff = now - start

      if (diff > 50000 && time_diff > 3) {
        doPrint(diff, now, start)
      }
  }

  def doPrint(diff:Long, now:Long, starter:Long) {
    println(s"Tried $total (${diff.toFloat/(now-starter)}/s)")
    last_total = total
    start = now
  }
}

class RepoUpdater(repo:Repository, hash_maker:ActorRef, root_dir:Path, public_username: String) extends Actor {
  import Messages._

  val starter = s"$public_username: "
  val ledger_file = root_dir.resolve("LEDGER.txt")
  val difficulty_file = root_dir.resolve("difficulty.txt")
  val porcelain = new git.Git(repo)

  def findDifficulty():String = {
    val diff_source = scala.io.Source.fromFile(difficulty_file.toString)
    val difficulty = diff_source.getLines().mkString("\n").trim()
    diff_source.close()
    difficulty
  }

  def updateLedger() {
    val source = scala.io.Source.fromFile(ledger_file.toString)
    var lines = List.empty[String]

    val found = source.getLines() exists { line =>
      if (line.startsWith(starter)) {
        val num = line.substring(line.indexOf(":") + 2).toInt + 1
        lines ::= s"$starter$num"
        true
      }
      else {
        lines ::= line.toString
        false
      }
    }
    if (!found) lines ::= s"${starter}1\n"
    source.close()

    // Write out the new ledger
    val new_ledger = lines.reverse.mkString("\n")
    Files.write(ledger_file, new_ledger.getBytes, StandardOpenOption.TRUNCATE_EXISTING)
    println(s"New Ledger!!! ~~~$new_ledger~~~")
  }

  def receive = {
    case UpdateRepo =>
      val before = repo.resolve("origin/master")
      try {
        porcelain.fetch().call()
      } catch {
        case ex:InvalidRemoteException => println(s"Failed to fetch from origin: $ex")
        case ex:TransportException => println(s"Failed to fetch from origin: $ex")
      }

      if (repo.resolve("origin/master") == before) {
        println("\tNothing to see here!")
        sender ! CarryOn
      }
      else {
        sender ! Pause
        hash_maker ! Pause
        porcelain.reset().setRef("origin/master").setMode(git.ResetCommand.ResetType.HARD).call()
        println(s"New head : ${repo.resolve("HEAD").name}")
        val difficulty = findDifficulty()
        updateLedger()

        val tree = porcelain.add().addFilepattern("LEDGER.txt").call().writeTree(repo.newObjectInserter)
        val commit = CommitMaker(tree, repo.resolve("HEAD"), new PersonIdent(repo))
        sender ! NewCommitShiz(commit, difficulty)
      }
  }
}

class RepoManager(miner:MinerInfo) extends Actor {
  import Messages._
  case class Reset()

  var paused = true
  val builder = new FileRepositoryBuilder()
  val repo = builder.setGitDir(miner.root_dir.resolve(".git").toFile).readEnvironment().build()

  var commit_maker:Option[CommitMaker] = None

  override def preStart() { self ! Ready }

  def do_push(expected_sha:ObjectId, nonce:String, parent:ObjectId) {
    if (repo.resolve("HEAD").name != parent.name) {
      println("Too Slow!")
      return
    }

    commit_maker foreach { case maker =>
      val commit = maker.makeCommit(nonce)
      val inserter = repo.newObjectInserter()
      val sha = inserter.insert(commit).name
      if (expected_sha.name != sha) {
        println("Hmmmm, apparently the way I make the sha is wrong :(")
        println(s"Expected: ${expected_sha.name} || Got: $sha")
      }
      else {
        println("PUSHING!!!!!!!!!!!!!!!!!!!!!!!!")
        val porcelain = new git.Git(repo)
        porcelain.reset().setRef(sha).setMode(git.ResetCommand.ResetType.HARD).call()
        try {
          porcelain.push().setRemote("origin").add("master").setOutputStream(System.out).call()
          println(":D  :D  :D  :D  :D  :D  :D  :D  :D  :D  :D  :D  :D")
        } catch {
          case ex:InvalidRemoteException => println(s"Failed to push to origin: $ex")
          case ex:TransportException => println(s"Failed to push to origin: $ex")
        }

        self ! Prepare
        context.become(preparing)
      }
    }
  }

  def receive = {
    case Ready =>
      if (!miner.root_dir.toFile.exists) {
        println(s"Cloning now to ${miner.root_dir}")
        new git.CloneCommand().setDirectory(miner.root_dir.toFile).setURI(miner.clone_url).call()
        println(s"Finished cloning!")
      }
      else {
        println(s"Playground at ${miner.root_dir} already exists, making sure it's origin url is ${miner.clone_url}")
        val config = repo.getConfig
        config.setString("remote", "origin", "url", miner.clone_url)
        config.save()
      }
      self ! Prepare
      context.become(preparing)
  }

  def preparing:Receive = {
    case Prepare =>
      println("Preparing the Repository!")
      implicit val timeout = Timeout(10 seconds)
      val updater = context.actorOf(Props(new RepoUpdater(repo, miner.hash_maker, miner.root_dir, miner.public_username)), "Updater")
      context.watch(updater)
      updater ! UpdateRepo

      def onFinish() = {
        paused = false
        context.unwatch(updater)
        updater ! PoisonPill
        context.system.scheduler.scheduleOnce(10.seconds, self, Update)
        context.become(waiting)
      }

      context.become({
        case Terminated(c) =>
          println(s"Failed to update the repo")
          self ! Prepare
          context.become(preparing)

        case Pause => paused = true

        case Push(sha, nonce, parent) if !paused && !commit_maker.isEmpty => do_push(sha, nonce, parent)

        case CarryOn => onFinish()

        case NewCommitShiz(commit, difficulty) =>
          onFinish()
          commit_maker = Some(commit)
          println("Repository is prepared!")
          miner.hash_maker ! MakeCommit(commit, difficulty)
      })
  }

  def waiting:Receive = {
    case Push(sha, nonce, parent) => do_push(sha, nonce, parent)
    case Update =>
      self ! Prepare
      context.become(preparing)
  }
}

object CounterApp extends App {
  val customConf = ConfigFactory.parseString(s"""
    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote {
        log-remote-lifecycle-events = off
        enabled-transports = ["akka.remote.netty.tcp"]
        netty.tcp {
          hostname = "127.0.0.1"
          port = 2551
        }
     }
    }
  """
  )

  val system = ActorSystem("Counter", ConfigFactory.load(customConf))
  val counter = system.actorOf(Props[Counter], "Counter")

  sys.addShutdownHook({
    system.stop(counter)
    system.shutdown()
  })
}

object HasherApp extends App {
  val processors = Runtime.getRuntime.availableProcessors() * 2
  val customConf = ConfigFactory.parseString(s"""
    akka {
      event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
      actor.provider = "akka.remote.RemoteActorRefProvider"
      actor.default-mailbox.mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"

      actor.deployment {
         /Hasher {
           mailbox-type = "miner.HasherMailbox"
         }

         /HashMaker {
           dispatcher = hashmaker-dispatcher
           router = round-robin
           nrOfInstances = ${processors * 4}
         }
      }

      remote {
        log-remote-lifecycle-events = off
        enabled-transports = ["akka.remote.netty.tcp"]
        netty.tcp {
          hostname = "127.0.0.1"
          port = 2552
        }
      }
    }

    hashmaker-dispatcher {
      mailbox-type = "akka.dispatch.BoundedMailbox"
      mailbox-capacity = 1
      mailbox-push-timeout-time = 0s

      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-min = 8
        parallelism-factor = 48
        throughput = 400
      }
    }
  """
  )

  val system = ActorSystem("Hasher", ConfigFactory.load(customConf))
  val counter = system.actorOf(Props(new LookupActor("akka.tcp://Counter@127.0.0.1:2551/user/Counter")), "Counter")
  val hash_maker = system.actorOf(Props[HashMaker], "HashMaker")
  val hasher = system.actorOf(Props(new Hasher(hash_maker, counter)), "Hasher")

  sys.addShutdownHook({
    system.stop(hasher)
    system.stop(hash_maker)
    system.shutdown()
  })
}

object RepoManager extends App {
    if (args.length < 3) {
      println("Please specify your username, playground and clone_url")
      System.exit(1)
    }

    val public_username = args(0)
    val root_dir = Paths.get(args(1))
    val clone_url = args(2)

    val done_file = root_dir.resolve("done").toFile
    if (done_file.exists()) done_file.delete()

    if (root_dir.toFile.exists()) FileUtils.deleteDirectory(root_dir.toFile)

    val processors = Runtime.getRuntime.availableProcessors()
    val customConf = ConfigFactory.parseString(s"""
    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"
      actor.default-mailbox.mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"

      remote {
        log-remote-lifecycle-events = off
        enabled-transports = ["akka.remote.netty.tcp"]
        netty.tcp {
          hostname = "127.0.0.1"
          port = 2553
        }
      }
    }
  """
    )

  val system = ActorSystem("Repo", ConfigFactory.load(customConf))
  val hash_maker = system.actorOf(Props(new LookupActor("akka.tcp://Hasher@127.0.0.1:2552/user/Hasher")), "Hasher")
  val miner_info = MinerInfo(root_dir, clone_url, public_username, hash_maker)
  system.actorOf(Props(new RepoManager(miner_info)), "RepoManager")
}
