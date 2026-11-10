package com.linfranca.ytdlpmobile

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.AppCompatSpinner

/** Unanchored choices: Android never tries to move this window back beside its field. */
internal object LandscapeChoices {
    private class Rows(private val source: Adapter): BaseAdapter() {
        override fun getCount()=source.count
        override fun getItem(position: Int): Any?=source.getItem(position)
        override fun getItemId(position: Int)=source.getItemId(position)
        override fun hasStableIds()=source.hasStableIds()
        override fun getViewTypeCount()=source.viewTypeCount.coerceAtLeast(1)
        override fun getItemViewType(position: Int)=source.getItemViewType(position)
        override fun areAllItemsEnabled()=(source as? ListAdapter)?.areAllItemsEnabled() ?: true
        override fun isEnabled(position: Int)=(source as? ListAdapter)?.isEnabled(position) ?: true
        override fun getView(position: Int,old: View?,parent: ViewGroup): View =
            if(source is SpinnerAdapter) source.getDropDownView(position,old,parent) else source.getView(position,old,parent)
    }
    fun show(view: View,source: Adapter,selected: Int,dismiss: ()->Unit,choose: (ListView,View?,Int)->Unit): Dialog? {
        val panelWidth=LandscapePanels.choiceWidth(view.context)
        if(panelWidth==0 || source.count==0 || !view.isEnabled || !view.isAttachedToWindow) return null
        val dialog=AlertDialog.Builder(view.context).setSingleChoiceItems(Rows(source),selected) { d,position ->
            val list=(d as AlertDialog).listView
            if(position in 0 until source.count) choose(list,list.getChildAt(position-list.firstVisiblePosition),position)
            d.dismiss()
        }.create()
        dialog.setOnDismissListener { dismiss() }
        // Set geometry before the first frame; no anchor, parent scrolling or popup repositioning.
        dialog.window?.apply {
            attributes=WindowManager.LayoutParams().apply { copyFrom(attributes);this.width=panelWidth;height=WindowManager.LayoutParams.MATCH_PARENT;gravity=Gravity.TOP or Gravity.RIGHT;x=0;y=0 }
        }
        dialog.show()
        return dialog
    }
}

class PanelAutoCompleteTextView @JvmOverloads constructor(c: Context,a: AttributeSet?=null,style: Int=androidx.appcompat.R.attr.autoCompleteTextViewStyle): AppCompatAutoCompleteTextView(c,a,style) {
    private var choice: Dialog?=null
    private var keepChoice=false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        keepChoice=!hasFocus && choice?.isShowing==true
        try { super.onWindowFocusChanged(hasFocus) } finally { keepChoice=false }
    }
    override fun showDropDown() {
        if(LandscapePanels.choiceWidth(context)==0) { super.showDropDown();return }
        if(choice?.isShowing==true) return
        val source=adapter ?: return
        val selected=(0 until source.count).firstOrNull { convertSelectionToString(source.getItem(it)).toString()==text.toString() } ?: -1
        choice=LandscapeChoices.show(this,source,selected,{ choice=null }) { list,row,position ->
            val value=source.getItem(position)
            setText(convertSelectionToString(value),false)
            setSelection(text.length)
            onItemClickListener?.onItemClick(list,row,position,source.getItemId(position))
        }
    }
    override fun dismissDropDown() { if(!keepChoice) { choice?.dismiss();choice=null };super.dismissDropDown() }
    override fun isPopupShowing()=choice?.isShowing==true || super.isPopupShowing()
    override fun onConfigurationChanged(config: Configuration) { dismissDropDown();super.onConfigurationChanged(config) }
    override fun onDetachedFromWindow() { choice?.dismiss();choice=null;super.onDetachedFromWindow() }
}

class PanelSpinner @JvmOverloads constructor(c: Context,a: AttributeSet?=null,style: Int=android.R.attr.spinnerStyle): AppCompatSpinner(c,a,style) {
    private var choice: Dialog?=null
    private var downX=0f
    private var downY=0f
    override fun performClick(): Boolean {
        if(LandscapePanels.choiceWidth(context)==0) return super.performClick()
        if(!isEnabled) return false
        if(choice?.isShowing==true) return true
        val source=adapter ?: return true
        choice=LandscapeChoices.show(this,source,selectedItemPosition,{ choice=null }) { _,_,position -> setSelection(position) }
        if(choice!=null) { playSoundEffect(SoundEffectConstants.CLICK);sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED) }
        return true
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(LandscapePanels.choiceWidth(context)==0) return super.onTouchEvent(e)
        if(!isEnabled) return false
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x;downY=e.y;isPressed=true }
            MotionEvent.ACTION_MOVE -> { val slop=ViewConfiguration.get(context).scaledTouchSlop;if(kotlin.math.abs(e.x-downX)>slop || kotlin.math.abs(e.y-downY)>slop) isPressed=false }
            MotionEvent.ACTION_UP -> { val click=isPressed;isPressed=false;if(click) performClick() }
            MotionEvent.ACTION_CANCEL -> isPressed=false
        }
        return true
    }
    override fun onConfigurationChanged(config: Configuration) { choice?.dismiss();choice=null;super.onConfigurationChanged(config) }
    override fun onDetachedFromWindow() { choice?.dismiss();choice=null;super.onDetachedFromWindow() }
}
