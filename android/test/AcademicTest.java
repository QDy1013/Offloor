package com.nopalou;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** JVM checks for Academic parsing and merge. Needs org.json on the class path; compile with Timetable.java, TimetableSchool.java.
 *  Optional arg: JSON produced by `node eval/test_nwpu_academic.cjs out.json`. Fixtures are synthetic. */
public class AcademicTest {
    static int checks;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    static void rejects(String name, String json) {
        try { new Academic(json); } catch (Exception e) { check(name, true); return; }
        throw new AssertionError(name);
    }
    static final String S = "\"version\":1,\"school\":\"西北工业大学\"";
    static final String EXAM = "{\"course\":\"线性代数\",\"time\":\"2026-01-12 14:00~16:00\",\"location\":\"教东A-101\",\"status\":\"\",\"date\":\"2026-01-12\",\"start\":\"14:00\",\"end\":\"16:00\",\"finished\":false}";
    static final String GRADE = "{\"semesterId\":\"321\",\"semester\":\"2025秋\",\"name\":\"高等数学\",\"score\":\"92\",\"gp\":4.0,\"credits\":5}";
    static final String GRADE2 = "{\"semesterId\":\"321\",\"semester\":\"2025秋\",\"name\":\"体育\",\"score\":\"优秀\",\"gp\":3.0,\"credits\":1}";

    public static void main(String[] args) throws Exception {
        Academic a = new Academic("{" + S + ",\"exams\":[" + EXAM + "],\"grades\":[" + GRADE + "," + GRADE2 + "],\"gpa\":3.9}");
        check("parse exams, grades, gpa", a.exams.size() == 1 && a.grades.size() == 2 && a.gpa == 3.9);
        check("days until exam", a.exams.get(0).daysFrom("2026-01-10") == 2 && a.exams.get(0).daysFrom("bad") == Integer.MIN_VALUE);
        check("credit-weighted semester gp", Academic.weighted(a.grades) == 3.833 && Academic.credits(a.grades) == 6);
        check("grouped by semester", a.bySemester().size() == 1 && a.bySemester().get("2025秋").size() == 2);
        check("missing part is distinguishable from empty", !new Academic("{" + S + ",\"exams\":null}").hasExams && new Academic("{" + S + ",\"exams\":[]}").hasExams);
        rejects("unknown school", "{\"version\":1,\"school\":\"某大学\"}");
        rejects("gpa out of range", "{" + S + ",\"gpa\":7}");
        rejects("exam without course", "{" + S + ",\"exams\":[{\"finished\":false}]}");
        check("bad exam clock dropped, not guessed", new Academic("{" + S + ",\"exams\":[{\"course\":\"A\",\"start\":\"25:00\",\"date\":\"2026-02-30\",\"finished\":true}]}").exams.get(0).start.isEmpty());

        Academic fresh = Academic.merge(a, "{" + S + ",\"exams\":[],\"grades\":null,\"gpa\":null,\"failed\":[\"grades\",\"gpa\"]}", 1000);
        check("failed parts keep previous copy", fresh.grades.size() == 2 && fresh.gpa == 3.9);
        check("successful part replaces (even with empty)", fresh.hasExams && fresh.exams.isEmpty());
        check("update times only for fresh parts", fresh.updated("exams") == 1000 && fresh.updated("grades") == 0);
        check("failed marker not stored", !fresh.json.has("failed"));
        Academic first = Academic.merge(null, "{" + S + ",\"exams\":null,\"grades\":[],\"gpa\":null,\"failed\":[\"exams\"]}", 5);
        check("first import with failed part stays absent", !first.hasExams && first.hasGrades);
        check("round trip", new Academic(fresh.encode()).grades.size() == 2);
        Academic mixed=Academic.merge(a,"{"+S+",\"grades\":["+GRADE.replace("321","123").replace("2025秋","2025春")+"],\"gradeSemestersRead\":[\"123\"],\"failed\":[\"grades\",\"exams\",\"gpa\"]}",2000);
        check("failed semester preserves other newly read semester grades",mixed.grades.size()==3 && mixed.incomplete("grades"));
        Academic emptyTerm=Academic.merge(mixed,"{"+S+",\"grades\":[],\"gradeSemestersRead\":[\"321\"],\"failed\":[\"grades\",\"exams\",\"gpa\"]}",3000);
        check("confirmed empty semester clears only that semester",emptyTerm.grades.size()==1 && emptyTerm.grades.get(0).semesterId.equals("123"));
        if (args.length > 0) {
            String node = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
            Academic n = Academic.merge(null, node, 1);
            check("node adapter output accepted by Java model", n.exams.size() == 3 && n.grades.size() == 2 && n.gpa == 3.9);
        }
        System.out.println(checks + " checks passed");
    }
}
