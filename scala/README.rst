Scala Solution
==============

Here lies the second version of my scala solution. The first version was a bit
messier.

This solution gets to 2.5 million hashes a second on my computer :)

Unfortunately it seems throwing more hardware at it doesn't make it any faster,
I think I need to play around with the JVM options for that....

It's powered by Akka and needs three programs running for it to work. The good
news is the programs can be started in any order and can be taken up and down
at will and they'll all just reconnect.

The three programs are:

	miner.CounterApp
		This receives Count messages and will log to the console how many hashes
		have been tried and a rough rate.

	miner.HasherApp
		This gets told when it needs to create a new commit and will keep
		trying new commit messages until it has a valid sha1 for that commit.

		It sends back a Push message to the thing that tells it to make hashes
		when it has a valid commit.

	miner.RepoManager
		This creates a repository to work on and will keep trying to fetch
		changes until it changes. When it changes it creates a new LEDGER.txt
		and tells the hasher to start hashing from a new parent.

		When it receives a Push message, it will attempt to push the sha as
		specified in the Push message.

To play around with it, either execute/follow the ansible or::

	$ ./infrastructure/startdocker.sh

And then::

	$ ./infrastructure/runcommand.sh counter
	$ ./infrastructure/runcommand.sh hasher
	$ ./infrastructure/runcommand.sh repo

Note that the first time you run one of them sbt will have to download all the
dependencies and this can take some time.

