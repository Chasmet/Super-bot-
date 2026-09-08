package com.chasmet.superbot;

import android.view.accessibility.AccessibilityNodeInfo;

final class TikTokScheduleVerifier {
    private TikTokScheduleVerifier() {}
    static boolean matchesTargetPackage(String platform,CharSequence packageName){
        if(packageName==null)return false;
        String[] allowed=PublicationAlarmReceiver.packagesFor(platform);if(allowed==null)return false;
        for(String p:allowed)if(p.contentEquals(packageName))return true;return false;
    }
    static boolean acceptedText(String text){return TikTokTextPolicy.accepted(text);}
    static boolean accepted(AccessibilityNodeInfo root){
        if(root==null||!root.isVisibleToUser()||root.isEditable())return false;
        if(acceptedText(label(root)))return true;
        for(int i=0;i<root.getChildCount();i++){
            AccessibilityNodeInfo c=root.getChild(i);if(c!=null){boolean ok=accepted(c);c.recycle();if(ok)return true;}
        }return false;
    }
    static boolean scheduleSummaryMatches(AccessibilityNodeInfo root,long when){
        if(root==null||when<=0)return false;
        // Limit evidence to the scheduling row, not the user's caption.
        for(String name:new String[]{"Programmer la publication","Schedule post","Publication programmée","Scheduled for"}){
            java.util.List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(name);
            if(nodes==null)continue;
            try{for(AccessibilityNodeInfo n:nodes){
                if(!n.isVisibleToUser()||n.isEditable())continue;
                AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(n);
                try{for(int level=0;current!=null&&level<3;level++){
                    StringBuilder text=new StringBuilder();append(current,text,0);
                    if(TikTokTextPolicy.summary(text.toString(),when))return true;
                    AccessibilityNodeInfo parent=current.getParent();current.recycle();current=parent;
                }}finally{if(current!=null)current.recycle();}
            }}finally{for(AccessibilityNodeInfo n:nodes)n.recycle();}
        }return false;
    }
    private static String label(AccessibilityNodeInfo n){
        CharSequence text=n.getText();if(text!=null)return text.toString();
        return n.getContentDescription()==null?"":n.getContentDescription().toString();
    }
    private static void append(AccessibilityNodeInfo n,StringBuilder out,int depth){
        if(n==null||depth>8||!n.isVisibleToUser()||n.isEditable())return;
        out.append(' ').append(label(n));
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){append(c,out,depth+1);c.recycle();}}
    }
}
