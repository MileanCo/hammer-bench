#!/bin/bash
# Author: Salman Niazi 2014
# This script broadcasts all files required for running a HOP instance.
# A password-less sign-on should be setup prior to calling this script


#load config parameters

#All Unique Hosts
All_Hosts=${All_NNs_In_Current_Exp[*]}
All_Unique_Hosts=$(echo "${All_Hosts[@]}" | tr ' ' '\n' | sort -u | tr '\n' ' ')


for i in ${All_Unique_Hosts[@]}
do
	connectStr="$HopsFS_User@$i"
	echo "Starting NN on $i"
	ssh $connectStr $HopsFS_Remote_Dist_Folder/sbin/hadoop-daemon.sh --script hdfs start namenode
done





