Python Solution
===============

My python solution uses multiprocessing and pypy to achieve 500k hashes
a second.

I attempted to use pygit but found I couldn't get it to work on 13.10, but with
the magic of docker I was able to get it to work on Ubuntu 12.10.

I didn't like pygit very much and so I ended up just subprocessing out to things.

Also, without pypy it was going at 200k Hashes a second, whereas with pypy it
manages to get to 500k a second.

It appears that libgit only got support for pypy a few days after I wrote the
Python version so I wouldn't have been able to use it with pypy anyway
https://github.com/libgit2/pygit2/pull/327.

Note that the docker stuff here is a copy of me playing with docker for
something unrelated.

Executing it
------------

Either execute/follow the ansible scripts on your own machine or just run::

	$ ./infrastructure/startdocker.sh

And when it's up::

	$ ./infrastructure/runcommand.sh mine <username> <clone_url>

To stop it::

	$ ./infrastructure/runcommand.sh finish

To start bash in the container::

	$ ./infrastructure/runcommand.sh attach

