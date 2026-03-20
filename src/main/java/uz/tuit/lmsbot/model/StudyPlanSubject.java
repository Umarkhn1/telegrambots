package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class StudyPlanSubject {
    private String name;
    private int credits;
    private Integer grade; // null = нет оценки
    private int semester;
}