#!/usr/bin/env python3
"""Run pure routing checks on JDK 17+ without an Android SDK."""
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

root = Path(__file__).resolve().parent.parent
src = root / "app/src/main/java/com/linfranca/ytdlpmobile"
policy = (src / "CaptureMonitoringPolicy.java").read_text(encoding="utf-8")
policy = policy[policy.index("final class CaptureMonitoringPolicy"):]
java = """
class CaptureRoutingSmoke {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String[] watch = {
            "https://archive.org/details/0121GSCG21",
            "https://www.dailymotion.com/video/xallb5a",
            "https://www.bilibili.tv/en/video/2044273921",
            "https://kick.com/gymskin/videos/01a1ade3-aa08-7e3d-8bc3-46652a630e81",
            "https://www.newgrounds.com/portal/view/1045804",
            "https://www.nicovideo.jp/watch/sm47010857",
            "https://odysee.com/@mayaclars:4/An-Example-(Video):1",
            "https://peer.tube/w/j9mhcUpp1xnXtDhSVGoUeQ",
            "https://rutube.ru/video/0159941b24c63763c4a9aed839fad682/",
            "https://vod.sooplive.com/player/211650145",
            "https://vimeo.com/256258003",
            "https://vkvideo.ru/video-38168721_456286497"
        };
        var actual = new java.util.HashSet<CaptureSiteRoutes.Site>();
        for(String url:watch) {
            var r=CaptureSiteRoutes.video(url);
            check(r!=null && !r.id.isBlank());
            check(r.id.equals(CaptureSiteRoutes.uploadId(url,r.site)));
            actual.add(r.site);
        }
        check(actual.size()==12);
        String[] live = {
            "https://kick.com/gymskin",
            "https://odysee.com/@mayaclars:4/An-Example:e",
            "https://rumble.com/v7hy134-example.html",
            "https://play.sooplive.com/dreads/298395129",
            "https://live.vkvideo.ru/alinushka"
        };
        for(String url:live) check(CaptureSiteRoutes.livePage(url).equals(url));
        check(CaptureSiteRoutes.livePage("https://www.sooplive.com/station/dreads").equals("https://play.sooplive.com/dreads"));
        check(CaptureSiteRoutes.livePage("https://rumble.com/user/example/videos").equals("https://rumble.com/user/example"));
        check(CaptureSiteRoutes.isSoopVod(watch[9]) && !CaptureSiteRoutes.isSoopVod(live[3]));
        check(CaptureSiteRoutes.uploads("https://kick.com/gymskin/about").equals("https://kick.com/gymskin/videos"));
        check(CaptureSiteRoutes.uploads("https://peer.tube/c/example@lostpod.space").equals("https://peer.tube/c/example@lostpod.space/videos"));
        check(CaptureSiteRoutes.uploads("https://www.nicovideo.jp/user/15943182?ref=watch").equals("https://www.nicovideo.jp/user/15943182/video"));
        check(CaptureSiteRoutes.uploads("https://www.bilibili.tv/en/space/1702404349").equals("https://www.bilibili.tv/en/space/1702404349"));
        check(CaptureSiteRoutes.uploads("https://www.dailymotion.com/user/example/videos").equals("https://www.dailymotion.com/user/example"));
        check(CaptureSiteRoutes.uploads("https://vkvideo.ru/@rentv").equals("https://vkvideo.ru/@rentv/videos"));
        check(CaptureSiteRoutes.uploads(watch[8]).equals(watch[8]));
        check(CaptureSiteRoutes.uploads(watch[5])==null);
        check(CaptureSiteRoutes.video("https://odysee.com/discover")==null);
        check(CaptureSiteRoutes.live("https://kick.com/categories")==null);
        check(CaptureSiteRoutes.live(watch[3])==null && CaptureSiteRoutes.live(watch[11])==null);
        check(CaptureSiteRoutes.video("https://rumble.com/v7hw9j-deferred-recording.html")==null);
        check(CaptureSiteRoutes.site("https://chzzk.naver.com/video/16046689")==null);
        check(CaptureSiteRoutes.video("https://www.youtube.com/watch?v=example")==null);
        check(CaptureSiteRoutes.video("https://net123movies.com/movie/example")==null);
        check(CaptureSiteRoutes.video("https://vimeo.com.example.net/123")==null);
        check(CaptureSiteRoutes.video("https://vimeo.com@elsewhere.invalid/123")==null);
        check(CaptureSiteRoutes.video("https://vimeo.com:8443/123")==null);
        check(CaptureSiteRoutes.video("https://www.dailymotion.com/playlist/abc")==null);
        check(CaptureSiteRoutes.video("https://dai.ly/xallb5a")==null);
        check(CaptureSiteRoutes.uploadId(watch[4], CaptureSiteRoutes.Site.VIMEO)==null);
        // Model the actual queue handoff: preserve the source ID, then append
        // the per-attempt UUID that MonitoringCoordinator puts in job.item.
        for(String url:live) {
            var site=CaptureSiteRoutes.live(url).site;
            String providerId=site==CaptureSiteRoutes.Site.KICK
                ? "01a1ade3-aa08-7e3d-8bc3-46652a630e81" : "some:complete:id";
            String source="Capture:"+site.name()+":"+providerId;
            String attempt=java.util.UUID.randomUUID().toString();
            String queued=source+":"+attempt;
            check(CaptureMonitoringPolicy.matchesQueuedSource(source,queued,site));
            check(CaptureMonitoringPolicy.matchesQueuedSource(source,source,site));
            check(!CaptureMonitoringPolicy.matchesQueuedSource(source+"-changed",queued,site));
            check(!CaptureMonitoringPolicy.matchesQueuedSource(source,source+":invalid",site));
            check(!CaptureMonitoringPolicy.matchesQueuedSource(source,queued+":"+attempt,site));
            check(!CaptureMonitoringPolicy.matchesQueuedSource(source,queued,null));
            var another=site==CaptureSiteRoutes.Site.KICK ? CaptureSiteRoutes.Site.VK : CaptureSiteRoutes.Site.KICK;
            check(!CaptureMonitoringPolicy.matchesQueuedSource(source,queued,another));
        }
        check(!CaptureMonitoringPolicy.matchesQueuedSource("Capture:KICK:","Capture:KICK:",CaptureSiteRoutes.Site.KICK));
        String vimeo="https://vimeo.com/creator/videos", kick="https://kick.com/creator",
               youtube="https://www.youtube.com/@creator";
        // An existing Vimeo rule stays on the legacy route even if its new
        // capture adapter could only return preview results.
        check(!CaptureMonitoringPolicy.useCapturedRoutes("",vimeo,""));
        check(CaptureMonitoringPolicy.bindingOnSave(false,"",vimeo,"",vimeo,"")==null);
        check(CaptureMonitoringPolicy.bindingOnSave(false,"",vimeo,"",kick,"")==null);
        check(vimeo.equals(CaptureMonitoringPolicy.bindingOnSave(true,null,null,null,vimeo,"")));
        check(CaptureMonitoringPolicy.useCapturedRoutes(vimeo,vimeo,""));
        check(kick.equals(CaptureMonitoringPolicy.bindingOnSave(false,vimeo,vimeo,"",kick,"")));
        check(!CaptureMonitoringPolicy.useCapturedRoutes(vimeo,kick,""));
        check(!CaptureMonitoringPolicy.useCapturedRoutes(kick,kick,"custom-rule"));
        check(CaptureMonitoringPolicy.bindingOnSave(true,null,null,null,kick,"custom-rule")==null);
        check(CaptureMonitoringPolicy.bindingOnSave(true,null,null,null,youtube,"")==null);
        System.out.println("Capture checks passed: 12 VOD routes, 5 live routes, queue identity and legacy rule preservation");
    }
}
"""
with TemporaryDirectory() as tmp:
    target = Path(tmp) / "CaptureRoutingSmoke.java"
    text = (src / "CaptureSiteRoutes.java").read_text(encoding="utf-8")
    index = text.index("/**")
    target.write_text(text[:index] + java + "\n" + text[index:] + "\n" + policy, encoding="utf-8")
    subprocess.run(["java", str(target)], check=True)
