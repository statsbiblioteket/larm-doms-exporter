#!/bin/bash

script_path=$(dirname $(readlink -f $0))
configfile=$(readlink -f $(dirname $(readlink -f $0))/../config/lde.infrastructure.properties)
$configfile

logdir=$HOME/logs
# Save the pid of this process for later use
this_pid=$$
# This process logs here (see redirection before calling main())
logfile=$logdir/run_Upload.$this_pid.log

main()
{

exit 0;
}

print_usage()
{
    echo "Usage: $(basename $0) "
    echo
    echo
    echo
    echo "Settings will be sourced from this file:"
    echo "$configfile"
    echo "and must include:"
    echo "fileOutputDirectory (the input directory to this script)"
    echo "ftpServer"
    echo "ftpUsername"
    echo "ftpPassword"
    echo
}


[ -z "$fileOutputDirectory" ] && print_usage && exit 2
[ -z "$ftpServer" ] && print_usage && exit 2
[ -z "$ftpUsername" ] && print_usage && exit 2
[ -z "$ftpPassword" ] && print_usage && exit 2

# Save all output to the logfile aswell
exec > >(tee $logfile) 2>&1

# off we go!
main
