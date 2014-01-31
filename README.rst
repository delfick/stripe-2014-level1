2014 Stripe Level1 Solutions
============================

Stripe did a CTF competition in January 2014 (stripe-ctf.com) which involved
programming challenges.

Level1 was a "GitCoin" implementation, i.e. a BitCoin implemented using git.

Essentially there was a git repository containing ``LEDGER.txt`` and
``difficulty.txt`` files.

The ``LEDGER.txt`` contained names and how many coins that name had and the
``difficulty.txt`` contained a string that the next commit had to be
lexicographically smaller than.

Each commit could only add one coin to any user, or introduce a new user with
1 coin.

I ended up getting a bit carried away and hacked together a solution in Bash,
Python and Scala.

This repository contains the final versions of these solutions.

