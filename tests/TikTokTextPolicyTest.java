package com.chasmet.superbot;
import java.util.*;
public class TikTokTextPolicyTest {
 static int count;
 static void check(boolean v){count++;if(!v)throw new AssertionError("case "+count);}
 public static void main(String[] args){
  TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));Calendar c=Calendar.getInstance();c.set(2026,8,9,15,0,0);long t=c.getTimeInMillis();
  check(TikTokTextPolicy.summary("Programmer la publication 9 septembre 15:00",t));
  check(TikTokTextPolicy.summary("sept. 9 à 15:00",t));
  check(TikTokTextPolicy.summary("09/09/2026 15h00",t));
  check(!TikTokTextPolicy.summary("19 septembre 15:00",t));
  check(!TikTokTextPolicy.summary("9 septembre 16:00",t));
  check(!TikTokTextPolicy.summary("9 septembre 15:01",t));
  check(!TikTokTextPolicy.summary("09/09/2027 15:00",t));
  check(!TikTokTextPolicy.summary("09/09/2026 115:00",t));
  check(TikTokTextPolicy.accepted("Votre publication est programmée."));
  check(TikTokTextPolicy.accepted("Post scheduled successfully"));
  for(String bad:new String[]{"Scheduled posts","publication_dispatched","Terminé","Votre vidéo n'est pas programmée","Erreur : publication programmée","Post not scheduled","Programmer la publication","Aucune publication programmée"})check(!TikTokTextPolicy.accepted(bad));
  System.out.println(count+" assertions OK");
 }
}
