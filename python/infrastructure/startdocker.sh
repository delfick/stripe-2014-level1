#!/bin/bash

set -x
set -e

# Make sure we're in this folder for this execution
cd $(dirname ${BASH_SOURCE})

CONTAINER=$(cat containername)
PROVISIONED_IMAGE_NAME="provisioned_${CONTAINER}"

INFRASTRUCTURE=$(pwd -P)
STRIPE=$(dirname ${INFRASTRUCTURE})
SOLUTIONS=$(dirname ${STRIPE})

ANSIBLE=${INFRASTRUCTURE}/ansible

function id_for () {
    docker images | awk '{ print $1,$3 }' | grep $1 | cut -f2 -d' '
}

ID=$(id_for stripe_provisioner)
docker build -t stripe_provisioner docker
NEWID=$(id_for stripe_provisioner)

if [[ ${ID} != ${NEWID} ]]; then
    docker rmi ${PROVISIONED_IMAGE_NAME} || true
    docker kill ${CONTAINER} || true
    docker rm ${CONTAINER} || true
fi

if docker inspect ${CONTAINER} > /dev/null; then
    echo "Already have a container!"
else
    echo "Starting the container!"
    if docker images | awk '{ print $1 }' | sort -u | grep ${PROVISIONED_IMAGE_NAME}; then
        BASE="${PROVISIONED_IMAGE_NAME}"
        CONTAINER_BASE=${CONTAINER}_BASE
    else
        BASE="stripe_provisioner"
        CONTAINER_BASE=${CONTAINER}_BASE
    fi

    VOLUMES="-v ${SOLUTIONS}:/opt/solutions:rw -v ${STRIPE}:/opt/stripe:rw -v ${ANSIBLE}:/opt/ansible:ro"


    if [[ ${BASE} == "stripe_provisioner" ]]; then
        if ! docker images | awk '{ print $1 }' | sort -u | grep ${CONTAINER_BASE}; then
            # Diverge from the provisioner to allow multiple containers
            docker build -t ${CONTAINER_BASE} - < <(echo "FROM stripe_provisioner")
        fi
    fi

    if ! docker run -i -d -p $OPEN_PORT:8000 ${VOLUMES} -name ${CONTAINER} ${CONTAINER_BASE} /sbin/init; then
        docker rm ${CONTAINER}
        exit 1
    fi
fi

./runcommand.sh provision
docker commit ${CONTAINER} ${PROVISIONED_IMAGE_NAME}
