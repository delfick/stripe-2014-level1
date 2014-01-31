FROM ubuntu:quantal

MAINTAINER Stephen Moore, stephen@delfick.com

RUN sed -i -e 's#archive.ubuntu.com#au.archive.ubuntu.com#g' /etc/apt/sources.list
RUN sudo apt-get update
RUN apt-get install -y software-properties-common
RUN add-apt-repository ppa:rquillo/ansible
RUN apt-get update
RUN apt-get install -y ansible

VOLUME ["/opt/ansible", "/opt/stripe", "/opt/solutions"]
ADD provision.sh /opt/commands/provision.sh

