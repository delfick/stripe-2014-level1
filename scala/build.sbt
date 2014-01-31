name := "scala_miner"

version := "1.0.0"

scalaVersion := "2.10.2"

scalacOptions ++= Seq("-deprecation", "-feature")

resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3",
  "com.typesafe.akka" % "akka-remote_2.10" % "2.2.3",
  "commons-io" % "commons-io" % "2.4",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "[2.1,)",
  "com.typesafe.akka" % "akka-contrib_2.10" % "2.3-M2"
)
