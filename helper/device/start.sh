#!/system/bin/sh
# Shadow Trackpad Fix - on-device supervisor. Installed into /data/local/tmp/shadowtrackpad by the
# Shadow Trackpad Fix app (at boot / every health check) or by the shadow-trackpad VM script.
#   sh start.sh start    start the supervisor if it is not running (idempotent); clears a stop
#   sh start.sh stop     stop helper and supervisor; it stays stopped until "start"
#   sh start.sh ensure   automatic start (app checks): does nothing if the user stopped it
#   sh start.sh restart  automatic restart (e.g. new app version): does nothing if stopped
#   sh start.sh status   helper status
# The helper's code is taken from the installed app's APK, falling back to a VM-pushed dex.
D=/data/local/tmp/shadowtrackpad
PKG=local.shadowtrackpadfix
umask 077
mkdir -p $D
# Only the shell user may read or change these files (the supervisor runs with shell rights).
chmod 700 $D 2>/dev/null
chmod 600 $D/* 2>/dev/null

classpath() {
    APK=$(pm path --user current $PKG 2>/dev/null | head -1 | cut -d: -f2)
    [ -z "$APK" ] && APK=$(pm path $PKG 2>/dev/null | head -1 | cut -d: -f2)
    if [ -n "$APK" ] && [ -r "$APK" ]; then echo "$APK"; else echo "$D/shadowtrackpad.dex"; fi
}

supervisor_alive() {
    [ -f $D/supervisor.pid ] && grep -q supervise /proc/$(cat $D/supervisor.pid)/cmdline 2>/dev/null
}

stop_all() {
    # Supervisor first, so it can't start a new helper; then stop the helper cleanly (it
    # releases keys/buttons/trackpad); finally sweep up any helper that was mid-start.
    supervisor_alive && kill $(cat $D/supervisor.pid) 2>/dev/null
    rm -f $D/supervisor.pid
    CLASSPATH=$(classpath) app_process / local.shadowtrackpad.Main stop > /dev/null 2>&1
    sleep 1
    pkill -f "local.shadowtrackpad.Main run" 2>/dev/null
}

# Automatic starts never override the user's "stop" (checked again under the start lock below).
case "$1" in
    ensure|restart)
        if [ -f $D/stop ]; then echo "stopped by user"; exit 0; fi
        ;;
esac

case "$1" in
    restart)
        stop_all
        exec sh $D/start.sh ensure
        ;;
    start|ensure)
        # One start at a time (the app and the Linux script may both start it at the same moment).
        if ! mkdir $D/start.lock 2>/dev/null; then
            if [ $(( $(date +%s) - $(stat -c %Y $D/start.lock 2>/dev/null || echo 0) )) -gt 30 ]; then
                # Stale (e.g. left over from before a reboot). Renaming is atomic, so if two starts
                # find it stale at once, only one of them wins the takeover.
                mv $D/start.lock $D/start.lock.stale.$$ 2>/dev/null || { echo "start already in progress"; exit 0; }
                rm -rf $D/start.lock.stale.$$
                mkdir $D/start.lock 2>/dev/null || { echo "start already in progress"; exit 0; }
            else
                sleep 3
                if supervisor_alive; then echo "already running"; else echo "start already in progress"; fi
                exit 0
            fi
        fi
        trap 'rmdir $D/start.lock 2>/dev/null' EXIT
        if [ "$1" = ensure ] && [ -f $D/stop ]; then echo "stopped by user"; exit 0; fi
        if supervisor_alive; then echo "already running"; exit 0; fi
        # An older unsupervised helper (started before the supervisor existed) is stopped first.
        CLASSPATH=$(classpath) app_process / local.shadowtrackpad.Main stop > /dev/null 2>&1
        # Only the user's start clears a stop. If a stop lands while an automatic start is under
        # way, the new supervisor sees the stop file and exits without starting the helper.
        [ "$1" = start ] && rm -f $D/stop
        (setsid sh $D/start.sh supervise < /dev/null > /dev/null 2>&1 &)
        sleep 1
        if supervisor_alive; then echo "started"; else echo "failed to start"; fi
        ;;
    stop)
        touch $D/stop
        stop_all
        echo "stopped"
        ;;
    status)
        CLASSPATH=$(classpath) app_process / local.shadowtrackpad.Main status 2>/dev/null
        supervisor_alive && echo "supervisor: running" || echo "supervisor: not running"
        ;;
    supervise)
        echo $$ > $D/supervisor.pid
        fails=0
        while [ ! -f $D/stop ]; do
            # If another supervisor has taken over, this one bows out.
            [ "$(cat $D/supervisor.pid 2>/dev/null)" = "$$" ] || exit 0
            if [ -f $D/daemon.log ] && [ "$(stat -c %s $D/daemon.log)" -gt 2000000 ]; then
                mv $D/daemon.log $D/daemon.log.1
            fi
            t0=$(date +%s)
            CLASSPATH=$(classpath) app_process / local.shadowtrackpad.Main run >> $D/daemon.log 2>&1
            [ -f $D/stop ] && break
            if [ $(( $(date +%s) - t0 )) -lt 30 ]; then fails=$((fails + 1)); else fails=0; fi
            echo "$(date '+%m-%d %H:%M:%S') supervisor: helper exited, restarting (quick failures in a row: $fails)" >> $D/daemon.log
            # Never give up; just slow down if it keeps failing quickly.
            if [ $fails -ge 5 ]; then sleep 60; else sleep 2; fi
        done
        rm -f $D/supervisor.pid
        ;;
    *)
        echo "usage: start.sh start|stop|ensure|restart|status"
        ;;
esac
