package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.*;

@SuppressLint("NewApi")
public class BotAccessibilityService extends AccessibilityService {
  private long last; private int tries; private String active="";
  private final android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable retry=this::tick;
  private android.content.SharedPreferences p(){return getSharedPreferences("superbot_bot_state",MODE_PRIVATE);}
  private boolean awake(){return PublicationAlarmReceiver.isSuperBotAwake(this);}

  @Override public void onAccessibilityEvent(AccessibilityEvent e){
    if(!awake()||e==null||e.getPackageName()==null)return;
    String id=p().getString("active_task_id",""); if(id.isEmpty())return;
    PublicationTask t=PublicationTaskRepository.find(this,id); if(t==null)return;
    String pkg=PublicationAlarmReceiver.packageFor(t.platform);
    if(pkg==null||!pkg.equals(e.getPackageName().toString())||System.currentTimeMillis()-last<430)return;
    h.removeCallbacks(retry);process(t);h.postDelayed(retry,760);
  }
  private void tick(){
    if(!awake()){h.removeCallbacks(retry);return;}
    String id=p().getString("active_task_id","");if(id.isEmpty())return;
    PublicationTask t=PublicationTaskRepository.find(this,id);if(t==null)return;
    process(t);h.removeCallbacks(retry);h.postDelayed(retry,760);
  }
  private void process(PublicationTask t){
    if(!t.id.equals(active)){active=t.id;tries=0;clearPicker(t.id);}
    if(++tries>420){mark(t,"TIKTOK_PAUSED","TIKTOK — délai dépassé");h.removeCallbacks(retry);return;}
    AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return;
    try{
      String pkg=PublicationAlarmReceiver.packageFor(t.platform);
      if(r.getPackageName()==null||pkg==null||!pkg.equals(r.getPackageName().toString()))return;
      if(isTikTok(t))tikTok(r,t);else generic(r,t);
    }finally{r.recycle();}
  }
  private void tikTok(AccessibilityNodeInfo r,PublicationTask t){
    String s=state(t.id);if("TIKTOK_PAUSED".equals(s))return;
    if("TIKTOK_CONFIRMING".equals(s)&&has(r,"publication programmée","post scheduled","scheduled")){finish(t,"PROGRAMMÉ");return;}
    if(has(r,"Date et heure de publication","Date and time of publication")){picker(r,t);return;}

    // Une fois les roues validées, TikTok revient sur la feuille "Plus d'options".
    // Il faut la fermer avant d'appuyer sur Publier. Sinon le bot réouvre l'horloge en boucle.
    if("TIKTOK_SCHEDULE_READY".equals(s)||"TIKTOK_RETURN_POST".equals(s)){
      if(has(r,"Plus d’options","Plus d'options","More options")||has(r,"Programmer la publication","Schedule post")){
        if(performGlobalAction(GLOBAL_ACTION_BACK)){
          mark(t,"TIKTOK_RETURN_POST","TIKTOK — retour vers l'écran Publier");
          return;
        }
      }
      if(click(r,"Publier","Post")){
        mark(t,"TIKTOK_CONFIRMING","TIKTOK — programmation envoyée");
        return;
      }
      return;
    }

    if(has(r,"Ta Story","Your Story")&&click(r,"Suivant","Next")){mark(t,"TIKTOK_NEXT","TIKTOK — Suivant");return;}
    if(has(r,"Programmer la publication","Schedule post")&&schedule(r)){clearPicker(t.id);mark(t,"TIKTOK_SCHEDULE_OPEN","TIKTOK — programmation ouverte");return;}
    boolean post=has(r,"Publier","Post","Brouillons","Drafts")||has(r,"Ajouter un lien","Add link")||has(r,"Plus d’options","Plus d'options","More options");
    if(post){
      if(!p().getBoolean("meta_ok_"+t.id,false)){if(!meta(r,t))return;mark(t,"TIKTOK_METADATA","TIKTOK — métadonnées vérifiées");return;}
      if(click(r,"Plus d’options","Plus d'options","More options")){mark(t,"TIKTOK_MORE_OPTIONS","TIKTOK — Plus d'options");return;}
      if(scroll(r)||swipeUp()){mark(t,"TIKTOK_FIND_MORE_OPTIONS","TIKTOK — recherche Plus d'options");return;}
    }
    if("TIKTOK_MORE_OPTIONS".equals(s)||"TIKTOK_FIND_SCHEDULE".equals(s)||"TIKTOK_FIND_MORE_OPTIONS".equals(s)){
      if(schedule(r)){clearPicker(t.id);mark(t,"TIKTOK_SCHEDULE_OPEN","TIKTOK — programmation ouverte");return;}
      if(scroll(r)||swipeUp()){mark(t,"TIKTOK_FIND_SCHEDULE","TIKTOK — recherche programmation");return;}
    }
    if(click(r,"Suivant","Next","Continuer","Continue"))mark(t,"TIKTOK_NEXT","TIKTOK — navigation");
  }

  private boolean meta(AccessibilityNodeInfo r,PublicationTask t){
    String want=PublicationAlarmReceiver.buildMetadata(t).trim();
    if(want.isEmpty()){p().edit().putBoolean("meta_ok_"+t.id,true).apply();return true;}
    List<AccessibilityNodeInfo> fs=new ArrayList<>();editable(r,fs);
    try{
      for(AccessibilityNodeInfo f:fs){
        if(!f.isVisibleToUser())continue;
        String cur=f.getText()==null?"":f.getText().toString().trim();
        if(want.equals(cur)){p().edit().putBoolean("meta_ok_"+t.id,true).apply();return true;}
        String d=desc(f);
        if(!(d.contains("description")||d.contains("caption")||d.contains("légende")||d.contains("legende")||fs.size()==1))continue;
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);boolean ok=set(f,want);
        if(!ok){android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cb!=null){cb.setPrimaryClip(android.content.ClipData.newPlainText("Super Bot",want));ok=f.performAction(AccessibilityNodeInfo.ACTION_PASTE);}}
        mark(t,"TIKTOK_METADATA_PENDING",ok?"TIKTOK — texte envoyé":"TIKTOK — texte inaccessible");return false;
      }
    }finally{recycle(fs);}
    if(click(r,"Ajouter une description","Add description","Add a description"))mark(t,"TIKTOK_METADATA_PENDING","TIKTOK — champ description ouvert");
    return false;
  }

  private void picker(AccessibilityNodeInfo r,PublicationTask t){
    if(Build.VERSION.SDK_INT<24){mark(t,"TIKTOK_PAUSED","TIKTOK — Android < 24");return;}
    if(t.scheduledAt<=System.currentTimeMillis()+60000){mark(t,"TIKTOK_PAUSED","TIKTOK — date dépassée ou trop proche");return;}
    Rect screen=new Rect();r.getBoundsInScreen(screen);if(screen.width()<=0||screen.height()<=0)return;
    Calendar target=Calendar.getInstance();target.setTimeInMillis(t.scheduledAt);
    Cols c=columns(r,screen,t.id);diagnostic(r,t,c,target,screen);
    if(c.ok()){
      Calendar date=parseDate(c.d.v);Integer hour=num(c.h.v),minute=num(c.m.v);
      if(date!=null&&hour!=null&&minute!=null){
        if(!sameDay(date,target)){verifyReset(t.id);wheel(r,c.d,target.after(date)?1:-1,t,"date → "+fmtDate(target));return;}
        int wh=target.get(Calendar.HOUR_OF_DAY);if(hour!=wh){verifyReset(t.id);wheel(r,c.h,dir(hour,wh,24),t,"heure → "+two(wh));return;}
        int wm=target.get(Calendar.MINUTE);if(minute!=wm){verifyReset(t.id);wheel(r,c.m,dir(minute,wm,60),t,"minutes → "+two(wm));return;}
        int n=p().getInt("picker_verified_"+t.id,0)+1;p().edit().putInt("picker_verified_"+t.id,n).apply();
        if(n<2){mark(t,"TIKTOK_PICKER_VERIFY","TIKTOK — double contrôle");return;}
        if(click(r,"Terminé","Done"))mark(t,"TIKTOK_SCHEDULE_READY","TIKTOK — date et heure validées");return;
      }
    }
    fallback(r,t,target,screen);
  }

  private void fallback(AccessibilityNodeInfo r,PublicationTask t,Calendar target,Rect screen){
    String k="fb_"+t.id+"_";android.content.SharedPreferences q=p();
    if(!q.getBoolean(k+"init",false)){
      Calendar now=Calendar.getInstance(),a=(Calendar)now.clone(),b=(Calendar)target.clone();zero(a);zero(b);
      int days=(int)Math.max(0,(b.getTimeInMillis()-a.getTimeInMillis())/86400000L);
      int hs=dist(now.get(Calendar.HOUR_OF_DAY),target.get(Calendar.HOUR_OF_DAY),24);
      int ms=dist(now.get(Calendar.MINUTE),target.get(Calendar.MINUTE),60);
      q.edit().putBoolean(k+"init",true).putInt(k+"d",days).putInt(k+"h",hs).putInt(k+"hd",dir(now.get(Calendar.HOUR_OF_DAY),target.get(Calendar.HOUR_OF_DAY),24)).putInt(k+"m",ms).putInt(k+"md",dir(now.get(Calendar.MINUTE),target.get(Calendar.MINUTE),60)).apply();
    }
    int d=q.getInt(k+"d",0),hh=q.getInt(k+"h",0),m=q.getInt(k+"m",0);
    float y=screen.top+screen.height()*.642f,xD=screen.left+screen.width()*.20f,xH=screen.left+screen.width()*.575f,xM=screen.left+screen.width()*.835f;
    if(d>0){if(step(r,xD,y,1,t,"date"))q.edit().putInt(k+"d",d-1).apply();return;}
    if(hh>0){int di=q.getInt(k+"hd",1);if(step(r,xH,y,di,t,"heure"))q.edit().putInt(k+"h",hh-1).apply();return;}
    if(m>0){int di=q.getInt(k+"md",1);if(step(r,xM,y,di,t,"minutes"))q.edit().putInt(k+"m",m-1).apply();return;}
    mark(t,"TIKTOK_PICKER_WAIT","TIKTOK — roues déplacées, attente lecture avant Terminé");
  }

  private boolean step(AccessibilityNodeInfo r,float x,float y,int direction,PublicationTask t,String name){
    if(scrollAt(r,x,y,direction)){last=System.currentTimeMillis();mark(t,"TIKTOK_PICKER_SCROLL","TIKTOK — roue "+name+" défilée");return true;}
    float den=getResources().getDisplayMetrics().density,d=Math.max(44f,16f*den);
    return gesture(x,y+(direction>0?d*.52f:-d*.52f),y+(direction>0?-d*.52f:d*.52f),t,"roue "+name);
  }
  private boolean scrollAt(AccessibilityNodeInfo n,float x,float y,int direction){
    if(n==null)return false;Rect b=new Rect();n.getBoundsInScreen(b);
    if(b.contains((int)x,(int)y)&&n.isScrollable()){
      int a=direction>0?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
      if(n.performAction(a))return true;
    }
    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){boolean ok=scrollAt(c,x,y,direction);c.recycle();if(ok)return true;}}
    return false;
  }
  private void wheel(AccessibilityNodeInfo r,L l,int direction,PublicationTask t,String what){
    if(l==null)return;if(scrollAt(r,l.x,l.y,direction)){last=System.currentTimeMillis();mark(t,"TIKTOK_PICKER_SCROLL","TIKTOK — "+what);return;}
    float den=getResources().getDisplayMetrics().density,d=Math.max(16f*den,l.sp*.95f);d=Math.min(d,80f*den);
    gesture(l.x,l.y+(direction>0?d*.55f:-d*.55f),l.y+(direction>0?-d*.55f:d*.55f),t,what);
  }
  private boolean gesture(float x,float a,float b,PublicationTask t,String what){
    if(Build.VERSION.SDK_INT<24)return false;Path pth=new Path();pth.moveTo(x,a);pth.lineTo(x,b);
    GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(pth,0,360)).build();
    boolean ok=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription g){h.removeCallbacks(retry);h.postDelayed(retry,720);}@Override public void onCancelled(GestureDescription g){h.removeCallbacks(retry);h.postDelayed(retry,720);}},null);
    last=System.currentTimeMillis();mark(t,"TIKTOK_PICKER_GESTURE",ok?"TIKTOK — "+what:"TIKTOK — geste refusé "+what);return ok;
  }

  private Cols columns(AccessibilityNodeInfo r,Rect screen,String id){
    List<AccessibilityNodeInfo> ns=new ArrayList<>();labels(r,ns);List<L> ds=new ArrayList<>(),hs=new ArrayList<>(),ms=new ArrayList<>();
    try{
      for(AccessibilityNodeInfo n:ns){if(!n.isVisibleToUser())continue;String tx=text(n);if(tx.isEmpty())continue;Rect b=new Rect();n.getBoundsInScreen(b);if(b.isEmpty())continue;
        float x=(b.centerX()-screen.left)/(float)screen.width();
        if(dateLabel(tx)&&x<.48f)ds.add(new L(tx,b.centerX(),b.centerY()));
        else if(tx.matches("[0-9]{1,2}")){if(x>=.43f&&x<.72f)hs.add(new L(tx,b.centerX(),b.centerY()));else if(x>=.72f)ms.add(new L(tx,b.centerX(),b.centerY()));}
      }
    }finally{recycle(ns);}
    int y=p().getInt("picker_center_y_"+id,-1);
    if(y<0){for(L z:ds){String n=norm(z.v);if(n.contains("aujourd'hui")||n.contains("today")){y=z.y;break;}}if(y<0&&!ds.isEmpty()){sort(ds);y=ds.get(0).y;}if(y>=0)p().edit().putInt("picker_center_y_"+id,y).apply();}
    return new Cols(near(ds,y),near(hs,y),near(ms,y),space(ds),space(hs),space(ms));
  }

  private void diagnostic(AccessibilityNodeInfo r,PublicationTask t,Cols c,Calendar target,Rect screen){
    StringBuilder s=new StringBuilder();s.append("task=").append(t.id).append('\n').append("state=").append(state(t.id)).append('\n')
      .append("target=").append(new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.FRANCE).format(target.getTime())).append('\n')
      .append("screen=").append(screen.left).append(',').append(screen.top).append(',').append(screen.right).append(',').append(screen.bottom).append('\n')
      .append("selectedDate=").append(c.d==null?"<missing>":c.d.v).append('\n')
      .append("selectedHour=").append(c.h==null?"<missing>":c.h.v).append('\n')
      .append("selectedMinute=").append(c.m==null?"<missing>":c.m.v).append("\nNODES\n");
    dump(r,s,0);getSharedPreferences("superbot_diagnostic",MODE_PRIVATE).edit().putString("clock",s.toString()).apply();
  }
  private void dump(AccessibilityNodeInfo n,StringBuilder s,int depth){
    if(n==null||s.length()>24000||depth>24)return;Rect b=new Rect();n.getBoundsInScreen(b);String tx=text(n);
    if(!tx.isEmpty()||n.isClickable()||n.isScrollable())s.append(depth).append("|class=").append(n.getClassName()).append("|text=").append(tx.replace('\n',' ')).append("|id=").append(n.getViewIdResourceName()).append("|bounds=").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append("|click=").append(n.isClickable()).append("|scroll=").append(n.isScrollable()).append("|actions=").append(n.getActions()).append('\n');
    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){dump(c,s,depth+1);c.recycle();}}
  }

  private static void sort(List<L>a){Collections.sort(a,new Comparator<L>(){public int compare(L x,L y){return x.y<y.y?-1:(x.y==y.y?0:1);}});}
  private static L near(List<L>a,int y){if(a.isEmpty()||y<0)return null;L b=null;int bd=Integer.MAX_VALUE;for(L z:a){int d=Math.abs(z.y-y);if(d<bd){bd=d;b=z;}}return b;}
  private static float space(List<L>a){if(a.size()<2)return 42f;sort(a);List<Integer>d=new ArrayList<>();for(int i=1;i<a.size();i++){int x=a.get(i).y-a.get(i-1).y;if(x>8)d.add(x);}if(d.isEmpty())return 42f;Collections.sort(d);return d.get(d.size()/2);}
  private static int dir(int c,int t,int mod){int f=(t-c+mod)%mod,b=(c-t+mod)%mod;return f<=b?1:-1;}
  private static int dist(int c,int t,int mod){int f=(t-c+mod)%mod,b=(c-t+mod)%mod;return Math.min(f,b);}
  private static Integer num(String s){if(s==null)return null;String n=s.replaceAll("[^0-9]","");if(n.isEmpty())return null;try{return Integer.parseInt(n);}catch(Exception e){return null;}}
  private static boolean dateLabel(String s){if(s==null)return false;String v=s.toLowerCase(Locale.FRANCE).replace("’","'");return v.contains("aujourd'hui")||v.contains("today")||v.matches(".*(janv|févr|fevr|mars|avr|mai|juin|juil|août|aout|sept|oct|nov|déc|dec).*\\d{1,2}.*");}
  private Calendar parseDate(String s){if(s==null)return null;String n=norm(s);Calendar today=Calendar.getInstance();zero(today);if(n.contains("aujourd'hui")||n.contains("today"))return today;for(int i=0;i<=31;i++){Calendar q=(Calendar)today.clone();q.add(Calendar.DAY_OF_YEAR,i);String day=""+q.get(Calendar.DAY_OF_MONTH),mo=norm(new SimpleDateFormat("MMM",Locale.FRANCE).format(q.getTime()));if(n.contains(mo)&&java.util.regex.Pattern.compile("(?<![0-9])"+day+"(?![0-9])").matcher(n).find())return q;}return null;}
  private static String norm(String s){return s.toLowerCase(Locale.FRANCE).replace(".","").replace("’","'").replace("é","e").replace("è","e").replace("ê","e").replace("û","u").replace("ù","u").replace("ô","o").replace("î","i").replace("ï","i").replace("à","a").trim();}
  private static void zero(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
  private static boolean sameDay(Calendar a,Calendar b){return a.get(Calendar.YEAR)==b.get(Calendar.YEAR)&&a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);}
  private static String fmtDate(Calendar c){return new SimpleDateFormat("dd/MM/yyyy",Locale.FRANCE).format(c.getTime());}
  private static String two(int v){return String.format(Locale.FRANCE,"%02d",v);}
  private void verifyReset(String id){p().edit().remove("picker_verified_"+id).apply();}
  private void clearPicker(String id){android.content.SharedPreferences.Editor e=p().edit().remove("picker_verified_"+id).remove("picker_center_y_"+id);String k="fb_"+id+"_";e.remove(k+"init").remove(k+"d").remove(k+"h").remove(k+"hd").remove(k+"m").remove(k+"md").apply();}
  private boolean schedule(AccessibilityNodeInfo r){return click(r,"Programmer la publication","Programmer","Schedule post","Schedule");}
  private boolean swipeUp(){if(Build.VERSION.SDK_INT<24)return false;android.util.DisplayMetrics d=getResources().getDisplayMetrics();Path pth=new Path();pth.moveTo(d.widthPixels*.5f,d.heightPixels*.78f);pth.lineTo(d.widthPixels*.5f,d.heightPixels*.38f);boolean ok=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(pth,0,420)).build(),null,null);if(ok)last=System.currentTimeMillis();return ok;}
  private static boolean scroll(AccessibilityNodeInfo r){if(r==null)return false;if(r.isScrollable()&&r.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(int i=0;i<r.getChildCount();i++){AccessibilityNodeInfo c=r.getChild(i);if(c!=null){boolean ok=scroll(c);c.recycle();if(ok)return true;}}return false;}
  private void generic(AccessibilityNodeInfo r,PublicationTask t){if(has(r,"publié","published","upload complete")){finish(t,"PUBLIÉ");return;}List<AccessibilityNodeInfo>fs=new ArrayList<>();editable(r,fs);try{String all=PublicationAlarmReceiver.buildMetadata(t);for(AccessibilityNodeInfo f:fs)if(f.isEditable()&&(f.getText()==null||f.getText().length()==0)&&set(f,all)){mark(t,state(t.id),"MÉTADONNÉES REMPLIES");return;}}finally{recycle(fs);}if(click(r,"Suivant","Next","Continuer","Continue"))mark(t,state(t.id),"NAVIGATION EN COURS");}
  private static void editable(AccessibilityNodeInfo n,List<AccessibilityNodeInfo>o){if(n==null)return;if(n.isEditable())o.add(AccessibilityNodeInfo.obtain(n));for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){editable(c,o);c.recycle();}}}
  private static void labels(AccessibilityNodeInfo n,List<AccessibilityNodeInfo>o){if(n==null)return;if((n.getText()!=null||n.getContentDescription()!=null)&&n.getChildCount()==0)o.add(AccessibilityNodeInfo.obtain(n));for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){labels(c,o);c.recycle();}}}
  private static String text(AccessibilityNodeInfo n){if(n.getText()!=null&&!n.getText().toString().trim().isEmpty())return n.getText().toString().trim();return n.getContentDescription()==null?"":n.getContentDescription().toString().trim();}
  private static String desc(AccessibilityNodeInfo n){StringBuilder b=new StringBuilder();if(n.getViewIdResourceName()!=null)b.append(n.getViewIdResourceName());if(n.getContentDescription()!=null)b.append(' ').append(n.getContentDescription());if(Build.VERSION.SDK_INT>=26&&n.getHintText()!=null)b.append(' ').append(n.getHintText());if(n.getText()!=null)b.append(' ').append(n.getText());return b.toString().toLowerCase(Locale.ROOT);}
  private static boolean set(AccessibilityNodeInfo n,String v){Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,v);return n.isEditable()&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
  private static boolean click(AccessibilityNodeInfo r,String...ls){for(String l:ls){List<AccessibilityNodeInfo>ns=r.findAccessibilityNodeInfosByText(l);if(ns==null)continue;try{for(AccessibilityNodeInfo n:ns)if(clickNode(n))return true;}finally{recycle(ns);}}return false;}
  private static boolean clickNode(AccessibilityNodeInfo n){AccessibilityNodeInfo c=AccessibilityNodeInfo.obtain(n);try{while(c!=null){if(c.isClickable()&&c.isEnabled())return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);AccessibilityNodeInfo x=c.getParent();c.recycle();c=x;}return false;}finally{if(c!=null)c.recycle();}}
  private static boolean has(AccessibilityNodeInfo r,String...ls){for(String l:ls){List<AccessibilityNodeInfo>ns=r.findAccessibilityNodeInfosByText(l);boolean f=ns!=null&&!ns.isEmpty();recycle(ns);if(f)return true;}return false;}
  private void mark(PublicationTask t,String s,String status){p().edit().putString("state_"+t.id,s).apply();t.status=status;PublicationTaskRepository.save(this,t);last=System.currentTimeMillis();}
  private String state(String id){return p().getString("state_"+id,"");}
  private void finish(PublicationTask t,String status){t.status=status;PublicationTaskRepository.save(this,t);p().edit().remove("active_task_id").remove("state_"+t.id).remove("picker_verified_"+t.id).remove("picker_center_y_"+t.id).remove("meta_ok_"+t.id).apply();h.removeCallbacks(retry);}
  private static boolean isTikTok(PublicationTask t){return t.platform!=null&&t.platform.toLowerCase(Locale.ROOT).contains("tiktok");}
  private static void recycle(List<AccessibilityNodeInfo>ns){if(ns!=null)for(AccessibilityNodeInfo n:ns)if(n!=null)n.recycle();}
  private static final class L{final String v;final float x;final int y;float sp=42f;L(String v,float x,int y){this.v=v;this.x=x;this.y=y;}}
  private static final class Cols{final L d,h,m;Cols(L d,L h,L m,float ds,float hs,float ms){this.d=d;this.h=h;this.m=m;if(d!=null)d.sp=ds;if(h!=null)h.sp=hs;if(m!=null)m.sp=ms;}boolean ok(){return d!=null&&h!=null&&m!=null;}}
  @Override public void onInterrupt(){}
  @Override public void onDestroy(){h.removeCallbacksAndMessages(null);super.onDestroy();}
}
