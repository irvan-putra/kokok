package com.vanipy.kokok
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.vanipy.kokok.FeedStore.feedUrl
import com.vanipy.kokok.FeedStore.verifyKeyset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class AlarmHistoryTest {
 @Test fun onlyTerminalRowsGoToHistory(){
  for(s in listOf("dismissed","canceled","missed"))assertTrue(AlarmListGrouping.isHistory(s))
  for(s in listOf("scheduled","fired","snoozed","unknown"))assertFalse(AlarmListGrouping.isHistory(s))
 }
 @Test fun historyToggleNeverChangesStoredAlarms(){
  val c=RuntimeEnvironment.getApplication();c.feedUrl="https://example.test/feed";c.verifyKeyset="{}"
  val now=System.currentTimeMillis();val rows=linkedMapOf(
   "synthetic-dismissed" to FeedStore.StoredAlarm("synthetic-dismissed","fictional test",now-60000,9,"dismissed",local=true),
   "synthetic-scheduled" to FeedStore.StoredAlarm("synthetic-scheduled","fictional future",now+60000,9,"scheduled",local=false),
   "synthetic-snooze" to FeedStore.StoredAlarm("synthetic-snooze","fictional snooze",now+30000,9,"scheduled",local=false,snoozed=true),
   "synthetic-fired" to FeedStore.StoredAlarm("synthetic-fired","fictional ringing",now,9,"fired",local=false))
  FeedStore.saveAlarms(c,rows);val a=Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
  val root=a.findViewById<ViewGroup>(android.R.id.content)
  assertTrue(root.findViewWithTag<View>("alarm-history-row")==null)
  root.findViewWithTag<View>("alarm-history-toggle").performClick()
  assertNotNull(root.findViewWithTag<View>("alarm-history-row"))
  root.findViewWithTag<View>("alarm-history-toggle").performClick()
  assertNull(root.findViewWithTag<View>("alarm-history-row"))
  assertEquals(rows,FeedStore.alarms(c))
 }
}
