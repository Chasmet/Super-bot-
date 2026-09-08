package com.chasmet.superbot;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

final class TikTokTextPolicy {
    private TikTokTextPolicy() {}
    static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ").trim();
    }
    static boolean accepted(String text) {
        String s=normalize(text);
        if(s.matches(".*\\b(erreur|echec|impossible|failed|error|not|pas|aucune|aucun|unsuccessful|couldn't|unable)\\b.*"))return false;
        return s.matches("(?:votre |ta |your )?(?:publication|video|post) (?:a ete |est |has been |was )?(?:programmee|scheduled)(?: avec succes| successfully)?[.!]?")
                || s.matches("(?:programmation reussie|scheduled successfully)[.!]?");
    }
    static boolean summary(String text,long timestamp) {
        String s=normalize(text).replace(".","");Date date=new Date(timestamp);
        String h=new SimpleDateFormat("H",Locale.ROOT).format(date),m=new SimpleDateFormat("mm",Locale.ROOT).format(date);
        boolean time=Pattern.compile("(?<![0-9])0?"+h+"\\s*[:h]\\s*"+m+"(?![0-9])").matcher(s).find();
        if(!time)return false;
        String day=new SimpleDateFormat("d",Locale.ROOT).format(date),month=new SimpleDateFormat("M",Locale.ROOT).format(date);
        String year=new SimpleDateFormat("yyyy",Locale.ROOT).format(date);
        // If a year is present, it must agree too.
        java.util.regex.Matcher years=Pattern.compile("\\b20[0-9]{2}\\b").matcher(s);
        while(years.find())if(!year.equals(years.group()))return false;
        if(Pattern.compile("(?<![0-9])0?"+day+"[/-]0?"+month+"(?![0-9])").matcher(s).find())return true;
        for(Locale locale:new Locale[]{Locale.FRANCE,Locale.ENGLISH})for(String form:new String[]{"MMM","MMMM"}){
            String mo=Pattern.quote(normalize(new SimpleDateFormat(form,locale).format(date)).replace(".",""));
            if(Pattern.compile("(?<![0-9])0?"+day+"\\s+"+mo+"\\b|\\b"+mo+"\\s+0?"+day+"(?![0-9])").matcher(s).find())return true;
        }
        Calendar today=Calendar.getInstance(),target=Calendar.getInstance();target.setTimeInMillis(timestamp);
        if(s.contains("demain")||s.contains("tomorrow"))today.add(Calendar.DAY_OF_YEAR,1);
        else if(!s.contains("aujourd'hui")&&!s.contains("today"))return false;
        return today.get(Calendar.YEAR)==target.get(Calendar.YEAR)&&today.get(Calendar.DAY_OF_YEAR)==target.get(Calendar.DAY_OF_YEAR);
    }
}
