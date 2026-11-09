package com.nopalou;

/** A running sync must remain incomplete while slow academic work is still pending. */
public class CampusSyncStateTest {
    private static int count;
    private static void check(String name,boolean value) {
        if (!value) throw new AssertionError(name);
        count++; System.out.println("PASS "+name);
    }
    public static void main(String[] args) {
        check("discovery has no invented percentage",CampusSyncState.percent(0,0,false)==0);
        check("completed campus work does not hide pending academic work",CampusSyncState.percent(6,8,false)==75);
        check("pending result save never displays 100 percent",CampusSyncState.percent(8,8,false)==99);
        check("complete result is successful",CampusSyncState.completed("complete"));
        check("partial result is a successful save",CampusSyncState.completed("partial"));
        check("interrupted result is not successful",!CampusSyncState.completed("interrupted"));
        check("only successful completion reaches 100",CampusSyncState.percent(8,8,true)==100);
        check("invalid counters stay bounded",CampusSyncState.percent(-1,8,false)==0 && CampusSyncState.percent(100,8,false)==99);
        check("queued login handoff displays progress immediately",CampusSyncState.visible(false,true,0,100));
        check("active sync stays visible",CampusSyncState.visible(true,false,0,100));
        check("completion remains briefly visible",CampusSyncState.visible(false,false,100,200));
        check("old completion no longer occupies page space",!CampusSyncState.visible(false,false,100,6100));
        check("clock rollback does not keep old completion visible",!CampusSyncState.visible(false,false,200,100));
        System.out.println(count+" checks passed");
    }
}
