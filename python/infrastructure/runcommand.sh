#!/bin/bash

cd $(dirname ${BASH_SOURCE})
CONTAINER=$(cat containername)

case $1 in
	bash|attach)
		cmd="/bin/bash"
		;;
	mine)
		shift
		cmd="/tmp/pypy/bin/pypy /opt/stripe/miner.py $@"
		;;
	finish)
		cmd="touch /opt/stripe/done"
		;;
	see_running)
		cmd="ps auxf | grep miner.py"
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

