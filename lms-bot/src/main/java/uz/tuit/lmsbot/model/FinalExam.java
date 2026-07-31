package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class FinalExam {
    private String subject;
    private String stream;
    private String date;
    private String from;      // время начала
    private String room;      // аудитория
    private String grade;     // балл
}