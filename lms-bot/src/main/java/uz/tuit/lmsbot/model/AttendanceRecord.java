package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class AttendanceRecord {
    private String date;
    private String type;
    private String calendar;
    private int hasReason;
    private String subject;
    private int total;   // used only for summary record
    private int missed;  // used only for summary record
    private boolean summary;
}
