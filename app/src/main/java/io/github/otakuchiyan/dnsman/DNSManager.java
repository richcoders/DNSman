/*
 - Author: otakuchiyan
 - License: GNU GPLv3
 - Description: The impletion and interface class
 */

package io.github.otakuchiyan.dnsman;

import android.content.Context;
import android.content.Intent;
import android.app.IntentService;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;
import android.support.v4.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class DNSManager {
    final static String SETPROP_COMMAND_PREFIX = "setprop net.dns";
	final static String GETPROP_COMMAND_PREFIX = "getprop net.dns";
        final static String SETRULE_COMMAND = "iptables -t nat %s OUTPUT -p %s --dport 53 -j DNAT --to-destination %s\n";
    final static String CHECKRULE_COMMAND_PREFIX = "iptables -t nat -L OUTPUT | grep ";

    final static String NDC_COMMAND_PREFIX = "ndc resolver";
    final static String SETIFDNS_COMMAND_BELOW_42 = NDC_COMMAND_PREFIX + " setifdns %s %s %s\n";
    final static String SETIFDNS_COMMAND = NDC_COMMAND_PREFIX + " setifdns %s '' %s %s\n";
    final static String SETNETDNS_COMMAND = NDC_COMMAND_PREFIX + " setnetdns %s '' %s %s\n";
    final static String SETDEFAULTIF_COMMAND = NDC_COMMAND_PREFIX + " setdefaultif";
    final static String FLUSHIF_COMMAND = NDC_COMMAND_PREFIX + " flushif %s";
    final static String FLUSHDEFAULTIF_COMMAND = NDC_COMMAND_PREFIX + " flushdefaultif";
    final static String FLUSHNET_COMMAND = NDC_COMMAND_PREFIX + " flushnet %s\n";

    //0 is no error
    final static int ERROR_SETPROP_FAILED = 1;
    final static int ERROR_UNKNOWN = 9999;

	final static String[] CHECKPROP_COMMANDS = {
        GETPROP_COMMAND_PREFIX + "1",
        GETPROP_COMMAND_PREFIX + "2"
    };

	private static Context context;

    private static String mode;
    private static String dns1;
    private static String dns2;
    private static String port;
    private static boolean checkProp;

	public static int setDNSViaSetprop(String dns1, String dns2, boolean checkProp) {
		String[] setCommands = {
            SETPROP_COMMAND_PREFIX + "1 \"" + dns1 + "\"",
            SETPROP_COMMAND_PREFIX + "2 \"" + dns2 + "\""
        };

        Log.d("DNSManager[CMD]", setCommands[0]);
        Log.d("DNSManager[CMD]", setCommands[1]);

        if(Shell.SU.available()){
            Shell.SU.run(setCommands);
        }else{
            Shell.SH.run(setCommands);
        }
    
        if(checkProp){
            List<String> result = Shell.SH.run(CHECKPROP_COMMANDS);

            //Check effect
            if(!result.get(0).equals(dns1) ||
                !result.get(1).equals(dns2)){
                return ERROR_SETPROP_FAILED;
            }
        }
		
        return 0;
    }

	public static int setDNSViaIPtables(String dns, String port){
        List<String> cmds = new ArrayList<>();
        List<String> result;

        String cmd1 = String.format(SETRULE_COMMAND, "-A", "udp", dns);
        String cmd2 = String.format(SETRULE_COMMAND, "-A", "tcp", dns);
        if(!port.equals("")){
            cmd1 += ":" + port;
            cmd2 += ":" + port;
        }

        cmds.add(cmd1);
        cmds.add(cmd2);

        Log.d("DNSManager[CMD]", cmd1);
        Log.d("DNSManager[CMD]", cmd2);

        return Shell.SU.run(cmds).isEmpty() ? 0 : ERROR_UNKNOWN;
    }

    public static int setDNSViaNdc(String iface, String dns1, String dns2){
        List<String> cmds = new ArrayList<>();

        String setdns_cmd;
        String flushdns_cmd;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { //>=5.0
            setdns_cmd = String.format(SETNETDNS_COMMAND, iface, dns1, dns2);

        }else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2){ //>=4.3
            setdns_cmd = String.format(SETIFDNS_COMMAND, iface, dns1, dns2);
        }else{ //<=4.2
            setdns_cmd = String.format(SETIFDNS_COMMAND_BELOW_42, iface, dns1, dns2);
        }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            flushdns_cmd = String.format(FLUSHNET_COMMAND, iface);
        }else{ //<=4.4
            flushdns_cmd = String.format(FLUSHIF_COMMAND, iface);
        }

        Log.d("DNSManaget[CMD]", setdns_cmd);
        Log.d("DNSManaget[CMD]", flushdns_cmd);

        cmds.add(FLUSHDEFAULTIF_COMMAND);
        cmds.add(flushdns_cmd);
        cmds.add(setdns_cmd);

        List<String> result = Shell.SU.run(cmds);

        return 0;
    }

	public static boolean isRulesAlivable(String dns, String port){
		String cmd = CHECKRULE_COMMAND_PREFIX + dns;

		if(!port.equals("")){
			cmd += ":" + port;
		}

		Log.d("DNSManager[CMD]", cmd);
		return !Shell.SU.run(cmd).isEmpty();
	}

    public static List<String> deleteRules(String dns, String port){
        List<String> cmds = new ArrayList<>();
        String cmd1 = String.format(SETRULE_COMMAND, "-D", "udp", dns);
        String cmd2 = String.format(SETRULE_COMMAND, "-D", "tcp", dns);
        if(!port.equals("")){
            cmd1 += ":" + port;
            cmd2 += ":" + port;
        }

        cmds.add(cmd1);
        cmds.add(cmd2);

        Log.d("DNSManager[CMD]", cmd1);
        Log.d("DNSManager[CMD]", cmd2);
        return Shell.SU.run(cmds);
    }
	
	public static String writeResolvConfig(String dns1, String dns2, String path){
        List<String> cmds = new ArrayList<>();
        boolean isSystem = false;
		
        if(path.substring(0, 7).equals("/system") ||
            path.substring(0, 4).equals("/etc")){
            isSystem = true;
        }
        if(isSystem){
            cmds.add("mount -o remount,rw /system");
        }
		if(!dns1.equals("")){
		    cmds.add("echo nameserver " + dns1 + " > " + path);
		}
		if(!dns2.equals("")){
			cmds.add("echo nameserver " + dns2 + " >> " + path);
		}
        cmds.add("chmod 644 " + path);
        if(isSystem){
            cmds.add("mount -o remount,ro /system");
        }

        StringBuilder sb = new StringBuilder();
		for(String s : Shell.SU.run(cmds)){
            sb.append(s);
            sb.append("\n");
        }
        return sb.toString();
	}
	
	public static String removeResolvConfig(String path){
        List<String> cmds = new ArrayList<>();
        boolean isSystem = false;

        if(path.substring(0, 7).equals("/system") ||
            path.substring(0, 4).equals("/etc")){
            isSystem = true;
        }

        if(isSystem){
            cmds.add("mount -o remount,rw /system");
        }

		cmds.add("rm " + path);

        if(isSystem){
			cmds.add("mount -o remount,ro /system");
        }

        StringBuilder sb = new StringBuilder();
		for(String s : Shell.SU.run(cmds)){
            sb.append(s);
            sb.append("\n");
        }
        return sb.toString();
	}
	
	public static List<String> getCurrentDNS(){
		return Shell.SH.run(CHECKPROP_COMMANDS);
	}

    public static String getResolvConf(String path){
        StringBuilder sb = new StringBuilder();
        for(String s : Shell.SH.run("cat " + path + " | grep 'nameserver'")){
            sb.append(s.replace("nameserver", ""));
            sb.append("\n");
        }
        return sb.toString();
    }

    
}
