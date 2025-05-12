document.addEventListener("DOMContentLoaded", function () {
    loadData();
});

function generateInputs() {
    let count = document.getElementById("subjectCount").value || 5;
    let coursesTable = document.getElementById("courses");
    coursesTable.innerHTML = "";

    for (let i = 0; i < count; i++) {
        let row = document.createElement("tr");
        row.innerHTML = `<td>${i + 1}</td>` +
                        `<td>
                            <select class="credits">
                                <option value="" selected hidden> </option>
                                <option value="2">2</option>
                                <option value="4">4</option>
                                <option value="6">6</option>
                            </select>
                        </td>` +
                        `<td>
                            <select class="grade">
                                <option value="" selected hidden> </option>
                                <option value="0">2</option>
                                <option value="3">3</option>
                                <option value="4">4</option>
                                <option value="5">5</option>
                            </select>
                        </td>`;
        coursesTable.appendChild(row);
    }

    loadSavedData(); // Подгружаем сохранённые данные после генерации
}

function calculateGPA() {
    let credits = document.querySelectorAll(".credits");
    let grades = document.querySelectorAll(".grade");
    
    let totalCredits = 0, totalPoints = 0;

    for (let i = 0; i < grades.length; i++) {
        let credit = parseFloat(credits[i].value);
        let grade = parseFloat(grades[i].value);
        
        if (!isNaN(credit) && credit > 0) {
            totalCredits += credit;
            totalPoints += grade * credit;
        }
    }

    let gpa = totalCredits > 0 ? totalPoints / totalCredits : 0;
    document.getElementById("result").innerText = `Ваш GPA: ${gpa.toFixed(2)}`;
    saveData();
}

function saveData() {
    let subjectCount = document.getElementById("subjectCount").value;
    let credits = Array.from(document.querySelectorAll(".credits")).map(e => e.value);
    let grades = Array.from(document.querySelectorAll(".grade")).map(e => e.value);

    localStorage.setItem("subjectCount", subjectCount);
    localStorage.setItem("credits", JSON.stringify(credits));
    localStorage.setItem("grades", JSON.stringify(grades));
}

function loadData() {
    let subjectCount = localStorage.getItem("subjectCount");
    
    if (subjectCount === null) {
        subjectCount = "";
    }
    
    document.getElementById("subjectCount").value = subjectCount;
    
    generateInputs(); // Генерируем таблицу

    loadSavedData(); // Загружаем сохранённые кредиты и оценки
}

function loadSavedData() {
    let credits = JSON.parse(localStorage.getItem("credits")) || [];
    let grades = JSON.parse(localStorage.getItem("grades")) || [];

    let creditsFields = document.querySelectorAll(".credits");
    let gradesFields = document.querySelectorAll(".grade");

    creditsFields.forEach((e, i) => {
        if (credits[i] !== undefined && credits[i] !== "") e.value = credits[i];
    });

    gradesFields.forEach((e, i) => {
        if (grades[i] !== undefined && grades[i] !== "") e.value = grades[i];
    });
}

function clearData() {
    localStorage.removeItem("subjectCount");
    localStorage.removeItem("credits");
    localStorage.removeItem("grades");

    document.getElementById("subjectCount").value = "";
    document.getElementById("result").innerText = "";

    generateInputs(); // Обновляем таблицу
}
