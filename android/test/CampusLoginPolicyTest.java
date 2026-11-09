package com.nopalou;

/** Regression: an update must not turn an expired session into mandatory password input. */
public class CampusLoginPolicyTest {
    private static int count;
    private static void check(String name,boolean result) {
        if (!result) throw new AssertionError(name);
        count++; System.out.println("PASS "+name);
    }
    public static void main(String[] args) {
        check("legacy session pause resumes with saved credentials",CampusLoginPolicy.recover("login",false,"student-a","student-a"));
        check("actual credential rejection stays paused",!CampusLoginPolicy.recover("login",true,"student-a","student-a"));
        check("school verification is not bypassed",!CampusLoginPolicy.recover("challenge",false,"student-a","student-a"));
        check("another account cannot resume the current snapshot",!CampusLoginPolicy.recover("login",false,"student-b","student-a"));
        check("missing credentials cannot auto login",!CampusLoginPolicy.recover("login",false,null,"student-a"));
        check("unbound identity cannot auto login",!CampusLoginPolicy.recover("login",false,"",""));
        check("healthy state is unchanged",!CampusLoginPolicy.recover("",false,"student-a","student-a"));
        System.out.println(count+" checks passed");
    }
}
