package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.List;

/** 独立引擎驱动的情境演示。只保留本页进度，不访问真实卡片、通知权限或 AI 接口。 */
public final class ShowcaseActivity extends Activity {
    private static final String CHECKPOINT="showcase-progress";
    private ShowcaseScenario scenario;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);Ui.apply(this);
        String checkpoint=saved==null?null:saved.getString(CHECKPOINT);
        try(InputStream model=getAssets().open("model.tsv")) {
            scenario=checkpoint==null?new ShowcaseScenario(model,System.currentTimeMillis()):ShowcaseScenario.restore(model,checkpoint);
        } catch(Exception error) {
            try(InputStream model=getAssets().open("model.tsv")) {
                scenario=new ShowcaseScenario(model,System.currentTimeMillis());
                Toast.makeText(this,"演示进度已重置",Toast.LENGTH_SHORT).show();
            } catch(Exception unavailable) {
                new AlertDialog.Builder(this).setTitle("暂时无法打开演示")
                        .setMessage("离线样例资源不可用，请重新打开或更新应用。")
                        .setPositiveButton("返回",(d,w)->finish()).setOnCancelListener(d->finish()).show();return;
            }
        }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if(scenario!=null)out.putString(CHECKPOINT,scenario.checkpoint());
        super.onSaveInstanceState(out);
    }
    private int dp(float value){return Ui.dp(this,value);}
    private TextView text(String value,int size,int color,boolean bold){
        TextView view=CourseUi.text(this,value,size,color,bold);view.setLineSpacing(dp(3),1);return view;
    }
    private LinearLayout panel(LinearLayout parent,int color) {
        LinearLayout view=CourseUi.column(this);view.setPadding(dp(18),dp(16),dp(18),dp(16));
        view.setBackground(Ui.round(this,color,20));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);parent.addView(view,params);return view;
    }
    private void render(){
        LinearLayout shell=CourseUi.column(this);shell.setBackgroundColor(Ui.BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),dp(6),dp(14),dp(6));
        header.addView(CourseUi.icon(this,"prev","返回",this::finish));
        header.addView(text("情境演示",22,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView reset=CourseUi.action(this,"重来",false,this::reset);header.addView(reset);
        shell.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout body=CourseUi.column(this);body.setPadding(dp(18),dp(10),dp(18),dp(10));scroll.addView(body);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout stage=panel(body,Ui.ACCENT_BG);
        stage.addView(text("构造场景 · 离线运行 · 步骤 "+scenario.step()+" / "+ShowcaseScenario.LAST_STEP,12,Ui.ACCENT,true));
        CourseUi.gap(stage,10);stage.addView(text(scenario.title(),23,Ui.TEXT,true));
        CourseUi.gap(stage,8);stage.addView(text(scenario.note(),14,Ui.SUB,false));
        CourseUi.gap(stage,12);stage.addView(text(scenario.summary(),12,Ui.ACCENT,true));
        CourseUi.gap(stage,6);stage.addView(text("样例数据独立展示，全程本机处理。",12,Ui.SUB,false));

        List<ShowcaseScenario.Message> messages=scenario.messages();
        if(!messages.isEmpty()) {
            LinearLayout input=panel(body,Ui.CARD);
            int first=scenario.step()==1?0:messages.size()-1;
            input.addView(text(scenario.step()==4||scenario.step()==6?"本次本人操作":"本次样例通知",12,Ui.SUB,true));
            for(int i=first;i<messages.size();i++){
                ShowcaseScenario.Message message=messages.get(i);CourseUi.gap(input,8);
                input.addView(text(TaskTrace.time(message.at)+" · "+message.actor,12,Ui.MUTE,false));
                TextView raw=text(message.text,14,Ui.TEXT,false);raw.setTextIsSelectable(true);input.addView(raw);
            }
        }
        if(scenario.awaitingReview()) {
            LinearLayout review=panel(body,Ui.AMBER_BG);
            review.addView(text("这条变更对应哪一项？",16,Ui.AMBER_TX,true));CourseUi.gap(review,8);
            for(Engine.Card card:scenario.reviewChoices()){
                review.addView(CourseUi.action(this,"关联到「"+card.displayTitle()+"」",true,()->{
                    if(scenario.choose(card.id))render();else problem();
                }),CourseUi.row(-2));CourseUi.gap(review,8);
            }
            review.addView(CourseUi.action(this,"忽略整条变更，保留原安排",false,()->{
                if(scenario.ignore())render();else problem();
            }),CourseUi.row(-2));
        }
        body.addView(text("当前事项",15,Ui.TEXT,true));CourseUi.gap(body,10);
        if(scenario.engine.all().isEmpty())body.addView(text("点击下方按钮，开始接收样例通知。",14,Ui.SUB,false));
        for(Engine.Card card:scenario.engine.all())addCard(body,card,false);
        if(!scenario.engine.removedCards().isEmpty()){
            CourseUi.gap(body,4);body.addView(text("已取消 · 保留来源记录",15,Ui.TEXT,true));CourseUi.gap(body,10);
            for(Engine.Card card:scenario.engine.removedCards())addCard(body,card,true);
        }
        CourseUi.gap(body,8);body.addView(text("构造样例，用于了解事项处理流程。",12,Ui.MUTE,false));

        LinearLayout footer=CourseUi.column(this);footer.setPadding(dp(18),dp(10),dp(18),dp(12));footer.setBackgroundColor(Ui.BG);
        TextView next=CourseUi.action(this,scenario.finished()?"再演示一次":scenario.nextLabel(),true,()->{
            if(scenario.finished())reset();else if(scenario.advance())render();else problem();
        });
        next.setEnabled(!scenario.awaitingReview());if(scenario.awaitingReview())next.setAlpha(.5f);
        footer.addView(next,CourseUi.row(-2));CourseUi.gap(footer,6);
        footer.addView(CourseUi.action(this,"查看本次回放记录",false,this::showRecap),CourseUi.row(-2));shell.addView(footer);
        setContentView(shell);Ui.systemBars(this,shell);
    }
    private void addCard(LinearLayout parent,Engine.Card card,boolean removed){
        int tone=removed?Ui.RED:card.done?Ui.GREEN:card.needsConfirm?Ui.AMBER_TX:Ui.ACCENT;
        LinearLayout box=panel(parent,Ui.CARD);
        box.addView(text(removed?"已取消":card.done?"本人已完成":card.needsConfirm?"待本人核对":card.updated?"已更新":"待办",12,tone,true));
        CourseUi.gap(box,6);box.addView(text(card.displayTitle(),18,Ui.TEXT,true));CourseUi.gap(box,8);
        box.addView(text((card.needsConfirm?"候选时间：":"安排时间：")+(card.deadline<=0?"待核对":TaskTrace.time(card.deadline)),14,Ui.SUB,false));
        CourseUi.gap(box,6);box.addView(text("查看原文和处理记录 ›",12,tone,false));
        box.setBackground(Ui.ripple(this,Ui.round(this,Ui.CARD,20),Ui.press(),20));
        box.setFocusable(true);box.setContentDescription(card.displayTitle()+"，查看来源和处理记录");box.setOnClickListener(v->showTrace(card));
    }
    private void showTrace(Engine.Card card){
        LinearLayout content=CourseUi.column(this);content.setPadding(dp(22),dp(12),dp(22),dp(18));
        TextView detail=text(TaskTrace.text(card),14,Ui.TEXT,false);detail.setTextIsSelectable(true);content.addView(detail);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(card.displayTitle()).setView(scroll).setPositiveButton("返回演示",null).show();
    }
    private void showRecap(){
        String recap=scenario.recap();
        new AlertDialog.Builder(this).setTitle("构造场景回放记录").setMessage(recap)
                .setPositiveButton("返回",null).setNeutralButton("复制记录",(d,w)->{
                    ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("构造场景回放记录",recap));Toast.makeText(this,"已复制构造场景记录",Toast.LENGTH_SHORT).show();}
                }).show();
    }
    private void reset(){
        try(InputStream model=getAssets().open("model.tsv")) {
            scenario=new ShowcaseScenario(model,scenario.startedAt);render();
        } catch(Exception error){problem();}
    }
    private void problem(){Toast.makeText(this,"这一步尚未完成，请核对当前事项或重新开始演示",Toast.LENGTH_LONG).show();}
}
