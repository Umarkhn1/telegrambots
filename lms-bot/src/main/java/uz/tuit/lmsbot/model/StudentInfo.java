package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class StudentInfo {
    private String fullName;
    private String birthDate;
    private String gender;
    private String recordBook;   // Зачётная книжка
    private String address;
    private String direction;    // Направление
    private String language;     // Язык обучения
    private String degree;       // Степень
    private String studyType;    // Тип обучения
    private String course;       // Курс
    private String group;        // Группа
    private String curator;      // Куратор
    private String scholarship;  // Стипендия
}