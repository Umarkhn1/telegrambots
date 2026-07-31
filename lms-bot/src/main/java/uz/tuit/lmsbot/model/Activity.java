package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class Activity {
    private String type;
    private String teacher;
    private String task;
    private String deadline;
    private String maxScore;
    private String earnedScore;

    /** Topshiriq namunasi (sample fayl) — col 2 dagi a[href] */
    private String sampleFileUrl;
    private String sampleFileName;

    /** Student yuklagan fayl — col 5 dagi a[href], agar mavjud bo'lsa */
    private String uploadedFileUrl;
    private String uploadedFileName;

    /** Upload uchun activity ID — upload button data-id yoki row index */
    private String activityId;

    // legacy compat
    public String getFileUrl()          { return sampleFileUrl; }
    public void   setFileUrl(String u)  { this.sampleFileUrl = u; }
    public String getUploadId()         { return activityId; }
    public void   setUploadId(String id){ this.activityId = id; }
}