package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class ScheduleEvent {
    private String title;
    private String start;
    private int type;
}