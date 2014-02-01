#!/bin/bash

if [[ -z $1 ]]; then
    echo "Please specify your username!"
    exit 1
fi
export public_username=$1

if [[ -z $2 ]]; then
    echo "Please specify the repo to work from!"
    exit 1
fi
export clone_url=$2

if [[ $3 == "--fast" ]]; then
    export fast=1
    echo "Ok, using the C loop"
    if ! gcc hasher.c -lcrypto -luuid -o hasher; then
        echo "Failed to compile the hasher :("
        exit 1
    fi
fi

# Work out where our done file is
# This file determines if we should stop now
export root_dir=$(pwd -P)
export done_file=$root_dir/done
export counterPipe=$root_dir/counter.pipe
echo "Done file at $done_file"

# Cleanup from previous run
rm $done_file -f
rm -rf trees
mkdir trees

rm -f $counterPipe
trap "rm -f $counterPipe" EXIT
[[ ! -p $counterPipe ]] && mkfifo $counterPipe

# Print out our counts
total=0
last_total=0
start=$(date +%s)

{
    echo "Looking for counts from $counterPipe"
    while true; do
        if read line <$counterPipe; then
            if [[ $line == "quit" || -f $done_file ]]; then
                echo "Counter says bye!"
                rm -f $counterPipe
                break
            fi
            
            ((total = $total + $line))
            now=$(date +%s)
            ((diff = $total - $last_total))
            ((time_diff = $now - $start))
            if (($diff > 500 && $time_diff > 5)); then
                rate=$( bc -l <<< "$diff/$time_diff" )
                printf "Tried %s (%.3f/s)\n" $total $rate
                last_total=$total
                start=$now
            fi
        fi
    done
} &

# Make sure the counter starts
sleep 1

# This function makes the commits
# We run many of it in parallel using xargs
makecommits() {
    num=$1
    tried=0
    failed=0
    attempts=0

    # Keep cloning till it works
    # Or fails 5 times
    while true; do
        if [[ -f $done_file ]]; then
            break
        fi

        if ((attempts > 5)); then
            failed=1
            break
        fi

        ((attempts=$attempts + 1))
        if git clone $clone_url trees/$num; then
            echo "Successfully cloned!"
            sleep 1
            break
        else
            rm -rf trees/$num
            sleep $num
        fi
    done

    if ((failed == 1)); then
        echo "Failed to create tree $num"
        break
    fi

    cd trees/$num
    here=$(pwd)
    update_file=$here/update

    # Keep making commits till the end of time now!
    while true; do
        if [[ -f $done_file ]]; then
            break
        fi

        if [[ -z $tree || -f $update_file ]]; then
            git prune
        
            # Only reset and recreate the index if the origin/master changed commit
            git fetch origin
            git reset --hard origin/master

            perl -i -pe 's/($ENV{public_username}: )(\d+)/$1 . ($2+1)/e' LEDGER.txt
            grep -q "$public_username" LEDGER.txt || echo "$public_username: 1" >> LEDGER.txt

            git update-index --add LEDGER.txt

            tree=$(git write-tree)
            parent=$(git rev-parse HEAD)
            difficulty=$(cat difficulty.txt)

            author=$(printf "%s <%s> 1390783500 +0000" "$(git config --get user.name)" "$(git config --get user.email)")
            base=$(printf "tree %s\nparent %s\nauthor %s\ncommitter %s\n\nGive me bitcoins!\n\nnonce:" "$tree" "$parent" "$author" "$author")
            ((base_length=$(echo $base | wc -c) + 1))
            rm $update_file -f
        fi

        # Keep trying to make a commit till it works
        if [[ ! -z $fast && $fast == 1 ]]; then
            # Let's use the C loop
            info=$(echo "$base" | $root_dir/hasher $difficulty $base_length $done_file $update_file $counterPipe)
            read sha1 nonce <<<$(IFS=";"; echo $info)
            next=$(printf "%s%s" "$base" "$nonce")
        else
            while true; do
                nonce=$(uuidgen)
                next=$(printf "%s%s" "$base" "$nonce")
                sha1=$( (printf "commit 280\0"; echo "$next") | sha1sum | awk '{ print $1 }')

                ((tried=$tried + 1))
                if ((tried % 500 == 0)); then
                    echo $tried > $counterPipe
                    ((tried = 0))
                    [[ -f $done_file ]] && break
                    [[ -f $update_file ]] && break
                fi

                if [[ "$sha1" == "000"* ]]; then
                    echo "===$nonce=== $parent"
                    echo -e "\t$sha1\n\t$difficulty\n\t---"
                fi

                if [[ "$sha1" < "$difficulty" ]]; then
                    break
                fi
            done
        fi

        [[ -f $done_file ]] && break
        [[ -f $update_file ]] && continue

        echo "Found one! $sha1"

        actualsha=$(echo "$next" | git hash-object -t commit -w --stdin)
        if [[ $actualsha == $sha1 ]]; then
            break
        else
            echo "The way we compute shas is wrong!!! $actualsha != $sha1 (tree $num)"
            echo "$next"
            touch $done_file
            break
        fi

        # Go to this commit and try to push it
        # If it passed, mission succeeded and we make a done file
        # Otherwise reset
        if git reset --hard $sha1; then
            if git push origin master; then
                echo "Yay it worked!!!"
            else
                echo "Damn, it didn't push :("
            fi
        else
            echo "Failed to checkout $sha1 for tree $num"
            touch $done_file
            break
        fi
        git reset --hard origin/master
    done
    echo "$num says bye!"
}

# Start making commits on our processors
export -f makecommits
((processors=$(grep -c ^processor /proc/cpuinfo) + 5))
seq $processors | xargs -I{} -n 1 -P $processors bash -c "makecommits {}" &

# Wait for them to be cloned in the first place
sleep 10

{
    playground=$root_dir/trees/tester
    git clone $clone_url $playground
    cd $playground
    while true; do
        if [[ -f $done_file ]]; then
            echo "quit" >> $counterPipe
            break
        fi
        sleep 3

        before=$(git rev-parse origin/master)
        git fetch origin
        if [[ $(git rev-parse origin/master) != $before ]]; then
            echo "Found new commit, trees should update now"
            for tree in $root_dir/trees/*; do
                touch $tree/update
            done
        fi
    done
}

