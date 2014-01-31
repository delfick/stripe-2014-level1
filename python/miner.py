#!/usr/bin/env python

from textwrap import dedent
import multiprocessing
import subprocess
import argparse
import hashlib
import shutil
import Queue
import uuid
import time
import sys
import os

def checked_loop(every=500):
    """
        Run an infinite loop
        And do some check every <every> iterations
    """
    count = 0
    yield True

    while True:
        count += 1
        if count % every == 0:
            yield True
        else:
            yield False

def silently(command, cwd, devnull):
    """Run a command silently"""
    subprocess.Popen(command, stdout=devnull, stderr=devnull, cwd=cwd).wait()

def tried_counter(queue, done_file):
    """
        Read counts from a queue
        And print sums of them with a rough speed count
    """
    total = 0
    start = time.time()
    last_total = total
    while True:
        if os.path.exists(done_file):
            print "Counter says bye!"
            return

        try:
            count = queue.get(timeout=3)
            total += count

            now = time.time()
            diff = total - last_total
            time_diff = now - start

            if diff > 50000 and time_diff > 5:
                print "Tried {} ({:.3f}/s)".format(total, float(diff)/(now - start))
                last_total = total
                start = now

        except Queue.Empty:
            pass

def keep_repos_updated(test_dir, done_file, tree_dir, processes):
    """
      Keep updating this repo until it changes
      Tell other repos to update when this happens
    """
    os.chdir(test_dir)
    devnull = open(os.devnull, 'w')

    while True:
        # Make sure we don't do this forever
        if not any(process.is_alive() for process in processes):
            open(done_file, "w").close()
        if os.path.exists(done_file): return

        before = subprocess.check_output(["git", "rev-parse", "origin/master"]).strip()
        silently(["git", "fetch", "origin"], test_dir, devnull)

        if subprocess.check_output(["git", "rev-parse", "origin/master"]).strip() != before:
            # Keep original repo up to date
            os.system("git reset --hard origin/master")

            # Tell all the trees to update
            for tree in os.listdir(tree_dir):
                open(os.path.join(tree_dir, tree, "update"), "w").close()

        time.sleep(3)
        os.system("git prune")

def startcommitter(num, *args):
    """
        Wrap makecommits
        So he says goodbye when he dies
    """
    try:
        makecommits(num, *args)
    finally:
        print "Committer {} says bye!".format(num)

def makecommits(num, username, done_file, tree_dir, clone_url, queue):
    """
        The main loop to make the commits
        Responsible for cloning the repository
        Keeping the repository up to date every time it finds a file called "update" in it's repo
        For updating the ledger and creating commits from this update
        and finally pushing a commit if it finds one lexicographically smaller than the contents of the difficulty file
    """
    tried = 0
    counter = 0
    devnull = open(os.devnull, 'w')
    clone_failed = False

    base = os.path.join(tree_dir, str(num))

    # Keep cloning till it works
    # Or fails 5 times
    attempts = -1
    while True:
        attempts = attempts + 1
        if os.path.exists(done_file): return

        if attempts > 5:
            clone_failed = True
            break

        command = "git clone {} {}".format(clone_url, base)
        ret = os.system(command)
        if ret == 0:
            print "Successfully cloned!"
            time.sleep(1)
            break
        else:
            if os.path.exists(base):
                shutil.rmtree(base)
            time.sleep(num)

    if clone_failed:
        print "Failed to create tree {}".format(num)
        return

    os.chdir(base)
    tmp_file = os.path.join(base, "tmp")
    update_file = os.path.join(base, "update")

    # Keep making commits till the end of time now!
    while True:
        if os.path.exists(update_file):
            os.remove(update_file)

        with open("difficulty.txt") as f:
            difficulty = f.read().strip()

        # Make sure we're up to date
        silently(["git", "fetch", "origin"], base, devnull)
        os.system("printf '{}: '; git reset --hard origin/master".format(num))

        # Prune so we don't slow down
        silently(["git", "prune"], base, devnull)

        # Update the ledger
        lines = []
        starter = "{}: ".format(public_username)

        with open("LEDGER.txt") as f:
            found = False
            for line in f:
                line = line.strip()
                if line.startswith(starter):
                    num = int(line[line.find(":")+2:])
                    lines.append("{}{}".format(starter, num+1))
                    found = True
                else:
                    lines.append(line)
            if not found:
                lines.append("{}1".format(starter))

        with open("LEDGER.txt", "w") as f:
            f.write('\n'.join(lines))

        # Add the ledger to the index
        os.system("git add LEDGER.txt")

        # Create a new tree
        tree = subprocess.check_output(["git", "write-tree"]).strip()
        parent = subprocess.check_output(["git", "rev-parse", "HEAD"]).strip()
        counter += 1

        # Start our sha hash
        commit = dedent("""
            tree {}
            parent {}
            author Stephen Moore <stephen@delfick.com> 1334500545 +0200
            committer Stephen Moore <stephen@delfick.com> 1334500545 +0200

            Give me a bitcoin!
            nonce:""".format(tree, parent)).strip()

        example_nonce = "{}:{}:{}".format(num, counter, uuid.uuid4().hex)
        commit_length = len("{}{}".format(commit, example_nonce))
        commit_base = "commit {}\0{}".format(commit_length, commit)
        shasofar = hashlib.sha1(commit_base)

        print "New Commit base!\n~~~{}~~~".format(commit)
        print "Length of a commit should be {}".format(commit_length)

        # Keep trying to make a commit till it works
        # If we found the update file, we need to update the repo
        while True:
            # Make the sha
            for finding_commit_check in checked_loop(every=100000):
                if finding_commit_check:
                    if os.path.exists(done_file): return
                    if os.path.exists(update_file): break

                nonce = "{}:{}:{}".format(num, counter, uuid.uuid4().hex)
                sha1 = shasofar.copy()
                sha1.update(nonce)
                sha1 = sha1.hexdigest()
                tried += 1

                if tried % 500000 == 0:
                    # Update the counter
                    queue.put(tried)
                    tried = 0

                # Determine if lexicographically less than difficulty
                if sha1 < difficulty:
                    break

            # Quit if we should
            if os.path.exists(done_file): return
            if os.path.exists(update_file): break

            print "*********************** {} Found one! {} (nonce: {})".format(num, sha1, nonce)

            # Write the commit to a file and hash it
            commit_string = "{}{}".format(commit, nonce)
            process = subprocess.Popen(["git", "hash-object", "-t", "commit", "-w", "--stdin"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
            process.stdin.write(commit_string)
            process.stdin.close()
            process.wait()

            actual_sha = process.stdout.read().strip()
            if actual_sha != sha1:
                print "Hmm, the way we make hashes is wrong :("
                print "~~~{}~~~".format(commit_string)
                print "Expected {}, got {}".format(sha1, actual_sha)
                with open(done_file, "w") as f: f.close()

            # Go to this commit and try to push it
            os.system("git reset --hard {}".format(sha1))

            # Reset no matter what happens
            os.system("git fetch origin")
            os.system("git reset --hard origin/master")
            break

if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit("Please specify name and clone_url")
    public_username = sys.argv[1]
    clone_url = sys.argv[2]

    # Work out where our done file is
    # This file determines if we should stop now
    root_dir = os.path.abspath(os.path.dirname(__file__))
    tree_dir = os.path.join(root_dir, "trees")
    done_file = os.path.join(root_dir, "done")
    print "Done file at {}".format(done_file)

    # Cleanup from previous run
    if os.path.exists(done_file):
        os.remove(done_file)
    if os.path.exists(tree_dir):
        shutil.rmtree(tree_dir)
    os.mkdir(tree_dir)

    # Start making commits on our processors
    queue = multiprocessing.Queue()
    processes = []
    for num in range(multiprocessing.cpu_count()):
        process = multiprocessing.Process(target=startcommitter, args=(num, public_username, done_file, tree_dir, clone_url, queue))
        process.start()
        processes.append(process)

    # Start the counter
    multiprocessing.Process(target=tried_counter, args=(queue, done_file)).start()

    # Watch for changes
    test_dir = os.path.join(tree_dir, "tester")
    subprocess.check_output(["git", "clone", clone_url, test_dir])
    keep_repos_updated(test_dir, done_file, tree_dir, processes)

