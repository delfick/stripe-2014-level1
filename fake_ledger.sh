#!/bin/bash
# Script to create a fake gitcoin ledger

print_help() {
    echo "This script creates a fake ledger for you so you can test the implementations"
    echo "'$0 create' will create the repo"
    echo "'$0 remove' will remove the repo"
    echo "'$0 recreate' will force recreate the repo"
    echo "'$0 change_difficulty <difficulty>' changes the difficulty to the new value specified"
    exit 0
}

do_remove() {
    rm -rf test_base
    rm -rf test_change
}

do_create() {
    force=$1

    if [[ ! -f test_base || $force == 1 ]]; then
        rm -rf test_base
        rm -rf test_change
        git init --bare test_base
        git clone test_base test_change
        cd test_change
        echo -e "Ledger\n======\nuser_a: 1\nuser_b: 3\n" > LEDGER.txt
        echo "0001" > difficulty.txt
        git add --all .
        git commit -m "initial"
        git push
    fi

    echo "==========================================="
    echo "==========================================="
    echo ""
    echo "For docker containers specify clone_url as"
    echo "file:///opt/solutions/test_base"
    echo ""
    echo "------------"
    echo ""
    echo "For outside docker, specify clone_url as"
    echo "file://$(pwd)/test_base"
    echo ""
    echo "==========================================="
    echo "==========================================="
}

change_difficulty() {
    difficulty=$1

    do_create
    echo $difficulty > difficulty.txt
    git commit -am "Changing difficulty"
    git push
}

{
    # Run the commands from where this script is
    cd $(dirname ${BASH_SOURCE})

    case $1 in
        help)
            print_help
            exit 1
            ;;
        create)
            do_create
            ;;
        remove)
            do_remove
            ;;
        recreate)
            do_create 1
            ;;
        change_difficulty)
            command=$1
            difficulty=$2
            if [[ -z $difficulty ]]; then
                echo "Please specify the new difficulty"
                exit 1
            fi
            change_difficulty $difficulty
            ;;
        *)
            print_help
            exit 1
            ;;
    esac
}
