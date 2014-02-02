Bash Solution
=============

Here is the bash solution.

When I originally did it, there was no C code and it ran at about 1000 hashes
a second.

I've since added some C code (enabled when you run the miner with "--fast" at
the end) and with this and the 8 cores on my machine it runs at 5 million hashes
a second.

This solution has three parts:

	Counter
		A process is run in the background reading counts from a named pipe and
		aggregating them for console output.
	
	Hashing
		Using the power of xargs, we spin off a number of processes in the
		background each creating their own repository to work with and will
		create hashes until a valid one is found and pushed.

		These will start again if it finds a "update" file in it's tree  and
		will quit all together if it finds a "done" file in bash directory.
	
	Updater
		The miner.sh itself will sit there with it's own clone to work from,
		fetching from origin every so often and putting a "update" file in all
		the hashing trees if there was a new commit.

There is also a "tester.sh" script here. It will create it's own ledger, create
a commit, get it's commit file and run it through the C program to see if it
comes up with the same sha.

Just run "./tester.sh" and it should say "Success".

