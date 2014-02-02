#!/bin/bash

nonce="1784152810-1-1391323802"

root_dir="$(dirname ${BASH_SOURCE})"
cd $root_dir

root_dir=$(pwd)
test_dir_base="$root_dir/test_base"
test_dir="$root_dir/test"
done_file="$root_dir/test_done"
update_file="$root_dir/test_update"
counter_file="$root_dir/test_counter"

rm -f $done_file
rm -f $update_file
rm -f $counter_file
rm -rf $test_dir
rm -rf $test_dir_base

difficulty="fffff"

git init --bare $test_dir_base
git clone $test_dir_base $test_dir
cd $test_dir

echo "Ledger\n====\nbob:1\njames:2\n" > $test_dir/LEDGER.txt
echo $difficulty > $test_dir/difficulty.txt
cd $test_dir
git add --all .
git commit -m "Initial"

echo "stephen:1\n" > $test_dir/LEDGER.txt
git commit -am "nonce:$nonce"

desired="$(git rev-parse HEAD)"
git reset --hard HEAD~

commit_base_original="$(git cat-file -p $desired)"
hash_object_says=$(echo "$commit_base_original" | git hash-object -t commit --stdin -w)
if [[ $hash_object_says != $desired ]]; then
	echo "Well, not even git hash-object agrees.... so to the pub!"
	echo "Expected $desired, git hash-object gave $hash_object_says"
	exit 1
fi

commit_base="$(echo "$commit_base_original" | sed -r 's/nonce:.+/nonce:/')"
commit_base_length="$(echo $commit_base | wc -c)"

echo ""
echo "Giving to hasher"
echo -e "~~~~~~~~~~~~~~~~~~~~\n$commit_base\n~~~~~~~~~~~~~~~"

gcc $root_dir/hasher.c -O3 -g -lcrypto -luuid -o $root_dir/hasher
info=$(echo "$commit_base" | $root_dir/hasher 1 $difficulty $commit_base_length $done_file $update_file $counter_file $nonce)

echo ""
echo "Got back from hasher"
read sha1 nonce <<<$(IFS=";"; echo $info)
echo "sha: $sha1"
echo "nonce: $nonce"

commit_base_constructed=$( printf "%s%s" "$commit_base" "$nonce" )
if [[ $commit_base_constructed != $commit_base_original ]]; then
	echo "The commit file we constructed is incorrect"
	echo -e "(original)~~~${commit_base_original}~~~\n"
	echo -e "(new)~~~${commit_base_constructed}~~~\n"
fi

if [[ "$sha1" != "$desired" ]]; then
	failed=1
else
	failed=0
fi

if ((failed == 1)); then
	echo ""
	echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
	echo "Failed to calculate the sha!"
	echo "Expected $desired"
	echo "=================="
	rm -f $done_file
	rm -f $update_file
	rm -f $counter_file
	exit 1
else
	echo "Succeeded!"
	rm -f $done_file
	rm -f $update_file
	rm -f $counter_file
	rm -rf $test_dir
	rm -rf $test_dir_base
	exit 0
fi

