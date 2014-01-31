#!/bin/bash

cd $(dirname ${BASH_SOURCE})
CONTAINER=$(cat containername)

case $1 in
	bash|attach)
		cmd="/bin/bash"
		;;
	counter)
		cmd="sbt 'run-main miner.CounterApp'"
		;;
	hasher)
		cmd="sbt 'run-main miner.HasherApp'"
		;;
	repo)
		shift
		cmd="sbt 'run-main miner.RepoManager $1 /tmp/playground $2'"
		;;
	provision)
		cmd="/bin/bash /opt/commands/provision.sh"
		;;
	*)
		echo "Please say what command to run"
		exit 1
		;;
esac

sudo lxc-attach -n `sudo docker inspect ${CONTAINER} | grep '"ID"' | sed 's/[^0-9a-z]//g'` -- /bin/bash -c "cd /opt/stripe && ${cmd}"

