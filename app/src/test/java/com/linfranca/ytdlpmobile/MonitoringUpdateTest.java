package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitoringUpdateTest {
 @Test public void unchangedMonitoringSnapshotKeepsExistingViews() {
  MonitoringRenderGate gate=new MonitoringRenderGate();
  assertTrue(gate.changed("rule A:waiting"));
  for(int i=0;i<20;i++)assertFalse(gate.changed("rule A:waiting"));
  assertTrue(gate.changed("rule A:live"));assertFalse(gate.changed("rule A:live"));
  assertTrue(gate.changed("no rules"));
 }
 @Test public void browserCookieWinsWithoutLosingUnrelatedImportedCookies() {
  assertEquals("a=browser; b=2; c=3",MonitoringCookieHeader.INSTANCE.merge("a=imported; b=2","a=browser; c=3"));
  assertEquals("",MonitoringCookieHeader.INSTANCE.merge("",""));
  assertEquals("a=1",MonitoringCookieHeader.INSTANCE.merge("a=1","bad=2\r\nInjected: secret"));
 }
 @Test public void verificationResponseIsNotOffline() {
  assertEquals("verification",MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/api","<title>Just a moment...</title>",403));
  assertEquals("verification",MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/api","<script src='/challenge-platform/test'></script>",403));
  assertNull(MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<script src='/challenge-platform/widget'></script><video></video>",200));
 }
 @Test public void onlyOpenConsentModalBlocksStaticPage() {
  assertEquals("agreement",MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<div aria-modal='true'>I agree to the terms</div>",200));
  assertEquals("age",MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<dialog open>Yes, I am over 18</dialog>",200));
  assertNull(MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<div hidden><div aria-modal='true'>I agree to the terms</div></div>",200));
  assertNull(MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<div style='display: none'><dialog open>Yes, I am over 18</dialog></div>",200));
  assertNull(MonitoringInteractionPolicy.INSTANCE.responseKind("https://example.com/channel","<p>Viewers over 18 can watch</p><input type='password' hidden>",200));
 }
 @Test public void interactionMessagesExplicitlyKeepLiveStateUnknown() {
  for(String kind:new String[]{"age","agreement","login","verification"}){
   assertTrue(MonitoringInteractionPolicy.INSTANCE.message(kind).contains("status unknown"));
   assertTrue(MonitoringInteractionPolicy.INSTANCE.message(kind).contains("Browser Assist"));
  }
 }
}
