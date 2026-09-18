package dev.bananajeans.pitwall.core;
import java.util.*;
public final class TelemetryTest {
    static int checks;
    static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
    public static void main(String[] args) {
        var marks=List.of(new Telemetry.Mark(40,"SF",true),new Telemetry.Mark(0,"SF",false),new Telemetry.Mark(12,"S2",false),new Telemetry.Mark(28,"S3",false));
        var laps=Telemetry.laps(marks);
        check(laps.size()==1,"Only complete laps");
        var lap=laps.get(0);
        check(lap.s1==12 && lap.s2==16 && lap.s3==12 && lap.estimated,"Ordered sectors and estimate provenance");
        check(Telemetry.laps(List.of(marks.get(1))).isEmpty(),"Ignore partial lap");
        check(Double.isNaN(Telemetry.laps(List.of(marks.get(0),marks.get(1))).get(0).s1),"Missing sector remains missing");
        var duplicate=new ArrayList<>(marks); duplicate.add(new Telemetry.Mark(14,"S2",false));
        check(Double.isNaN(Telemetry.laps(duplicate).get(0).s1),"Ambiguous sector remains missing");
        check(Double.isNaN(Telemetry.at(List.of(new Telemetry.Point(0,1),new Telemetry.Point(1,2)),.5)),"Gaps are not interpolated");
        check(Telemetry.averageKmh(400,40)==36,"Average speed uses known length");
        check(Double.isNaN(Telemetry.averageKmh(0,40)),"Unknown distance is not speed");
        var signal=new ArrayList<Telemetry.Point>();
        var flat=new ArrayList<Telemetry.Point>();
        for(int i=0;i<=6500;i++) { double t=i*.02; signal.add(new Telemetry.Point(t,2+Math.sin(t*2*Math.PI/40)+.6*Math.sin(t*14*Math.PI/40))); flat.add(new Telemetry.Point(t,0)); }
        var candidates=Telemetry.suggest(signal,5,40);
        check(candidates.size()==3,"Find three repeating synthetic laps");
        check(Math.abs(candidates.get(0).t-45)<.11 && candidates.get(0).estimated,"Candidate time and provenance");
        check(Telemetry.suggest(flat,5,40).isEmpty(),"No hallucinated laps on flat signal");
        boolean invalid=false; try { Telemetry.suggest(signal,5,0); } catch(IllegalArgumentException e) { invalid=true; }
        check(invalid,"Reject invalid configuration");
        check(ReleaseVersion.newer("v0.10.0","0.2.0"),"Compare versions numerically");
        check(!ReleaseVersion.newer("v0.2.0","0.2.0"),"Ignore current version");
        check(!ReleaseVersion.newer("v0.1.0","0.2.0"),"Ignore downgrade");
        check(!ReleaseVersion.newer("v0.3.0-beta","0.2.0"),"Ignore prerelease");
        check(!ReleaseVersion.newer("v999999999999.0.0","0.2.0"),"Reject overflow");
        check(!ReleaseVersion.newer("v0.03.0","0.2.0"),"Reject malformed version");
        System.out.println("PASS: "+checks+" telemetry checks");
    }
}
