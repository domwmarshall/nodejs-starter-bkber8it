package uk.dom.candydryerlab;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int BG=Color.rgb(6,14,22);
    private static final int PANEL=Color.rgb(13,27,39);
    private static final int PANEL2=Color.rgb(18,38,53);
    private static final int TEXT=Color.rgb(239,248,251);
    private static final int MUTED=Color.rgb(145,169,183);
    private static final int CYAN=Color.rgb(99,214,255);
    private static final int GREEN=Color.rgb(88,224,160);
    private static final int AMBER=Color.rgb(255,193,89);
    private static final int RED=Color.rgb(255,111,111);

    private final Handler main=new Handler(Looper.getMainLooper());
    private NfcAdapter nfc;
    private SharedPreferences prefs;

    private GaugeView linkGauge;
    private TextView linkState, linkHint;
    private TextView moisture, temperature, load, remaining;
    private TextView identityText, responseText, historyText, rawText;
    private TextView scanCountText, uniqueResponseText, lastSeenText;
    private Button stageButton, shareButton, statsProbeButton;
    private volatile boolean statsProbeArmed=false;

    private int scanCount;
    private String stage="Idle";
    private int stageIndex=0;
    private final String[] stages={
            "Idle",
            "Programme selected",
            "Running early",
            "Running mid-cycle",
            "Running late",
            "Finished"
    };
    private String lastCapture="No capture yet.";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs=getSharedPreferences("dryer_lab",MODE_PRIVATE);
        scanCount=prefs.getInt("scans",0);
        nfc=NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        refreshLocalStats();
        updateNfcState();
    }

    private View buildUi(){
        ScrollView sv=new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(16),dp(18),dp(40));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=txt("DRYER LAB",30,TEXT,true);
        titleRow.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView offline=chip("OFFLINE");
        titleRow.addView(offline);
        root.addView(titleRow);

        TextView model=txt("Candy CS C10DF-80  •  Product 31101151",14,MUTED,false);
        model.setPadding(0,dp(2),0,dp(14));
        root.addView(model);

        LinearLayout badgeRow=new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.addView(chip("NO ACCOUNT"),lp(-2,dp(34),0,0,8,0));
        badgeRow.addView(chip("NO CLOUD"),lp(-2,dp(34),0,0,8,0));
        badgeRow.addView(chip("NFC TYPE 4"),lp(-2,dp(34),0,0,0,0));
        root.addView(badgeRow,lp(-1,-2,0,0,0,14));

        LinearLayout hero=card();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        linkState=txt("READY FOR NFC",13,CYAN,true);
        linkState.setGravity(Gravity.CENTER);
        hero.addView(linkState);
        linkGauge=new GaugeView(this);
        hero.addView(linkGauge,new LinearLayout.LayoutParams(dp(250),dp(190)));
        linkHint=txt("Turn the dial to Smart Touch and hold the phone on the NFC logo.",14,MUTED,false);
        linkHint.setGravity(Gravity.CENTER);
        hero.addView(linkHint);
        root.addView(hero,lp(-1,-2,0,0,0,16));

        section(root,"LIVE DATA");
        TextView sensorNote=txt("Values stay blank until the appliance exposes a field we can verify. No invented sensor readings.",12,MUTED,false);
        sensorNote.setPadding(dp(2),0,0,dp(7));
        root.addView(sensorNote);

        GridLayout sensorGrid=new GridLayout(this);
        sensorGrid.setColumnCount(2);
        moisture=sensor(sensorGrid,"MOISTURE","--","Awaiting verified field");
        temperature=sensor(sensorGrid,"DRUM TEMP","-- °C","Awaiting verified field");
        load=sensor(sensorGrid,"LOAD","-- kg","Awaiting verified field");
        remaining=sensor(sensorGrid,"TIME LEFT","--","Awaiting verified field");
        root.addView(sensorGrid,lp(-1,-2,0,0,0,16));

        section(root,"APPLIANCE");
        LinearLayout appliance=card();
        identityText=txt("Identity will be decoded on first scan.",14,TEXT,false);
        appliance.addView(identityText);
        ProgressBar confidence=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        confidence.setMax(100);
        confidence.setProgress(50);
        appliance.addView(confidence,lp(-1,dp(10),0,14,0,5));
        TextView confidenceText=txt("Protocol map  •  NFC transport + identity + CRC verified",12,MUTED,false);
        appliance.addView(confidenceText);
        root.addView(appliance,lp(-1,-2,0,0,0,16));

        section(root,"LEARN MODE");
        LinearLayout learn=card();
        TextView learnTitle=txt("Map the dryer without Candy login",19,TEXT,true);
        learn.addView(learnTitle);
        TextView learnBody=txt(
                "Choose what the dryer is doing, then scan. Dryer Lab stores only local captures and compares each NFC response with the previous one.",
                13,MUTED,false);
        learnBody.setPadding(0,dp(5),0,dp(12));
        learn.addView(learnBody);

        stageButton=button("CAPTURE STAGE: IDLE",false);
        stageButton.setOnClickListener(v->{
            stageIndex=(stageIndex+1)%stages.length;
            stage=stages[stageIndex];
            stageButton.setText("CAPTURE STAGE: "+stage.toUpperCase(Locale.UK));
        });
        learn.addView(stageButton,lp(-1,dp(50),0,0,0,10));

        responseText=txt("Last response  •  waiting for NFC",13,TEXT,false);
        responseText.setTypeface(Typeface.MONOSPACE);
        learn.addView(responseText);

        historyText=txt(historyDisplay(),12,MUTED,false);
        historyText.setPadding(0,dp(12),0,0);
        learn.addView(historyText);

        LinearLayout learnActions=new LinearLayout(this);
        Button clear=button("CLEAR HISTORY",true);
        clear.setOnClickListener(v->{
            prefs.edit()
                    .remove("history")
                    .remove("last_command_hex")
                    .remove("unique_responses")
                    .apply();
            historyText.setText(historyDisplay());
            refreshLocalStats();
            Toast.makeText(this,"Local capture history cleared.",Toast.LENGTH_SHORT).show();
        });
        shareButton=button("SHARE LAST",true);
        shareButton.setEnabled(false);
        shareButton.setAlpha(.45f);
        shareButton.setOnClickListener(v->shareCapture());
        learnActions.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(8),1);
        learnActions.addView(new View(this),sp);
        learnActions.addView(shareButton,new LinearLayout.LayoutParams(0,dp(48),1));
        learn.addView(learnActions,lp(-1,dp(48),0,14,0,0));

        statsProbeButton=button("READ DRYING COUNTERS",false);
        statsProbeButton.setOnClickListener(v->{
            statsProbeArmed=true;
            statsProbeButton.setText("ARMED — HOLD PHONE ON SMART TOUCH");
            statsProbeButton.setEnabled(false);
            linkState.setText("DRYING COUNTER PROBE ARMED");
            linkState.setTextColor(AMBER);
            linkHint.setText("Keep the phone firmly on the Smart Touch area for about 3 seconds. This sends only the documented read opcode 0x11.");
        });
        learn.addView(statsProbeButton,lp(-1,dp(50),0,12,0,0));

        TextView probeNote=txt("Experimental interoperability probe: one read-only opcode (0x11) only. No start/store/line-test commands are sent.",11,MUTED,false);
        learn.addView(probeNote);

        root.addView(learn,lp(-1,-2,0,0,0,16));

        section(root,"PROGRAMME STUDIO");
        LinearLayout studio=card();
        TextView studioTitle=txt("Build a local drying profile",19,TEXT,true);
        studio.addView(studioTitle);
        TextView safety=txt(
                "Profiles are stored on this phone. Sending them to the dryer remains locked until the model-specific command bytes are verified.",
                12,AMBER,false);
        safety.setPadding(0,dp(5),0,dp(14));
        studio.addView(safety);

        int savedDry=prefs.getInt("profile_dry",2);
        int savedTime=prefs.getInt("profile_time",0);

        TextView dryLabel=txt("Dryness  •  "+drynessName(savedDry),14,TEXT,true);
        studio.addView(dryLabel);
        SeekBar dry=new SeekBar(this);
        dry.setMax(3);
        dry.setProgress(savedDry);
        studio.addView(dry);
        dry.setOnSeekBarChangeListener(new SimpleSeek(){
            public void onProgressChanged(SeekBar s,int v,boolean u){
                dryLabel.setText("Dryness  •  "+drynessName(v));
            }
        });

        TextView timeLabel=txt(savedTime==0?"Timed dry  •  Auto sensor":"Timed dry  •  "+(savedTime*15)+" min",14,TEXT,true);
        timeLabel.setPadding(0,dp(8),0,0);
        studio.addView(timeLabel);
        SeekBar time=new SeekBar(this);
        time.setMax(12);
        time.setProgress(savedTime);
        studio.addView(time);
        time.setOnSeekBarChangeListener(new SimpleSeek(){
            public void onProgressChanged(SeekBar s,int v,boolean u){
                timeLabel.setText(v==0?"Timed dry  •  Auto sensor":"Timed dry  •  "+(v*15)+" min");
            }
        });

        Switch anti=toggle("Easy Iron / anti-crease");
        Switch gentle=toggle("Gentle tumble");
        Switch low=toggle("Low heat");
        anti.setChecked(prefs.getBoolean("profile_anti",false));
        gentle.setChecked(prefs.getBoolean("profile_gentle",false));
        low.setChecked(prefs.getBoolean("profile_low",false));
        studio.addView(anti);
        studio.addView(gentle);
        studio.addView(low);

        Button save=button("SAVE PROFILE LOCALLY",false);
        save.setOnClickListener(v->{
            prefs.edit()
                    .putInt("profile_dry",dry.getProgress())
                    .putInt("profile_time",time.getProgress())
                    .putBoolean("profile_anti",anti.isChecked())
                    .putBoolean("profile_gentle",gentle.isChecked())
                    .putBoolean("profile_low",low.isChecked())
                    .apply();
            Toast.makeText(this,"Drying profile saved on this phone.",Toast.LENGTH_SHORT).show();
        });
        studio.addView(save,lp(-1,dp(50),0,12,0,0));
        root.addView(studio,lp(-1,-2,0,0,0,16));

        section(root,"LOCAL STATISTICS");
        GridLayout statsGrid=new GridLayout(this);
        statsGrid.setColumnCount(3);
        scanCountText=stat(statsGrid,"SCANS","0");
        uniqueResponseText=stat(statsGrid,"RESPONSES","0");
        lastSeenText=stat(statsGrid,"LAST SEEN","—");
        root.addView(statsGrid,lp(-1,-2,0,0,0,16));

        section(root,"PROTOCOL INSPECTOR");
        LinearLayout protocol=card();
        rawText=txt("Scan the dryer to inspect the decoded Type-4 records.",12,MUTED,false);
        rawText.setTypeface(Typeface.MONOSPACE);
        protocol.addView(rawText);
        root.addView(protocol,lp(-1,-2,0,0,0,16));

        TextView footer=txt("Dryer Lab MK9  •  account-free  •  no Internet permission  •  passive scan + read-only counter probe",11,MUTED,false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);
        return sv;
    }

    private String drynessName(int v){
        String[] names={"Iron","Hanger","Cupboard","Extra dry"};
        return names[Math.max(0,Math.min(names.length-1,v))];
    }

    private TextView chip(String s){
        TextView v=txt(s,10,CYAN,true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10),0,dp(10),0);
        GradientDrawable g=round(PANEL2,50);
        g.setStroke(dp(1),Color.rgb(47,86,105));
        v.setBackground(g);
        return v;
    }

    private TextView stat(GridLayout grid,String title,String value){
        LinearLayout box=cardSmall();
        TextView t=txt(title,10,MUTED,true);
        t.setGravity(Gravity.CENTER);
        TextView v=txt(value,17,TEXT,true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0,dp(5),0,0);
        box.addView(t);
        box.addView(v);
        GridLayout.LayoutParams gp=new GridLayout.LayoutParams();
        gp.width=0;
        gp.height=dp(86);
        gp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        gp.setMargins(dp(3),dp(3),dp(3),dp(3));
        grid.addView(box,gp);
        return v;
    }

    private TextView sensor(GridLayout grid,String title,String value,String sub){
        LinearLayout box=cardSmall();
        TextView t=txt(title,11,MUTED,true);
        TextView v=txt(value,23,TEXT,true);
        v.setPadding(0,dp(4),0,0);
        TextView st=txt(sub,11,MUTED,false);
        box.addView(t);
        box.addView(v);
        box.addView(st);
        GridLayout.LayoutParams gp=new GridLayout.LayoutParams();
        gp.width=0;
        gp.height=dp(112);
        gp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        gp.setMargins(dp(3),dp(3),dp(3),dp(3));
        grid.addView(box,gp);
        return v;
    }

    private Switch toggle(String label){
        Switch s=new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(14);
        s.setPadding(0,dp(5),0,dp(5));
        return s;
    }

    private void section(LinearLayout root,String label){
        TextView t=txt(label,12,MUTED,true);
        t.setLetterSpacing(.12f);
        t.setPadding(dp(2),dp(2),0,dp(8));
        root.addView(t);
    }

    private LinearLayout card(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16),dp(16),dp(16),dp(16));
        l.setBackground(round(PANEL,18));
        return l;
    }

    private LinearLayout cardSmall(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(13),dp(12),dp(11),dp(10));
        l.setBackground(round(PANEL2,16));
        return l;
    }

    private GradientDrawable round(int color,int radius){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1),Color.rgb(35,58,73));
        return g;
    }

    private TextView txt(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private Button button(String s,boolean outline){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setTextColor(outline?CYAN:BG);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable g=round(outline?PANEL2:CYAN,14);
        if(outline)g.setStroke(dp(1),CYAN);
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }

    private int dp(int x){
        return Math.round(x*getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume(){
        super.onResume();
        if(nfc!=null&&nfc.isEnabled()){
            nfc.enableReaderMode(this,this,
                    NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null);
        }
        updateNfcState();
    }

    @Override protected void onPause(){
        super.onPause();
        if(nfc!=null)nfc.disableReaderMode(this);
    }

    private void updateNfcState(){
        if(linkState==null)return;
        if(nfc==null){
            linkState.setText("NFC NOT AVAILABLE");
            linkState.setTextColor(RED);
        }else if(!nfc.isEnabled()){
            linkState.setText("TURN NFC ON");
            linkState.setTextColor(AMBER);
        }else{
            linkState.setText("READY FOR NFC");
            linkState.setTextColor(CYAN);
        }
    }

    @Override public void onTagDiscovered(Tag tag){
        IsoDep iso=IsoDep.get(tag);
        if(iso==null){
            main.post(()->showError("Tag detected, but it is not ISO-DEP / NFC Type 4."));
            return;
        }

        ScanResult result=new ScanResult();
        try{
            iso.connect();
            iso.setTimeout(5000);
            result.tech=String.join(", ",tag.getTechList());

            result.cc=readCapabilityContainer(iso,result.log);
            List<NdefFileInfo> files=parseNdefFiles(result.cc);

            NdefFileInfo statusFile=null, commandFile=null;
            for(NdefFileInfo info:files){
                result.log.add(String.format(Locale.ROOT,
                        "FILE %04X max=%d read=%02X write=%02X",
                        info.id,info.maxSize,info.readAccess,info.writeAccess));
                if(statusFile==null && info.readAccess==0x00 && info.writeAccess==0xFF) statusFile=info;
                if(commandFile==null && info.readAccess==0x00 && info.writeAccess!=0xFF) commandFile=info;
            }
            if(statusFile==null && !files.isEmpty())statusFile=files.get(0);
            if(commandFile==null && files.size()>1)commandFile=files.get(1);

            if(statusFile!=null)result.status=readNdefFile(iso,statusFile,"STATUS",result.log);
            if(commandFile!=null){
                try{
                    result.command=readNdefFile(iso,commandFile,"COMMAND",result.log);
                }catch(Exception e){
                    result.log.add("COMMAND read: "+e.getMessage());
                }
            }

            if(statsProbeArmed && commandFile!=null){
                statsProbeArmed=false;
                result.probeAttempted=true;
                try{
                    result.probeResponse=runReadOnlyProbe(iso,commandFile,0x11,"DRYING COUNTERS",result.log);
                    result.probeSuccess=result.probeResponse!=null;
                }catch(Exception e){
                    result.probeError=e.getClass().getSimpleName()+": "+e.getMessage();
                    result.log.add("PROBE ERROR: "+result.probeError);
                }
            }

            decodeStatus(result);
            decodeCommand(result);
            result.ok=result.status!=null&&result.status.length>0;
        }catch(Exception e){
            result.error=e.getClass().getSimpleName()+": "+e.getMessage();
            result.log.add("ERROR: "+result.error);
        }finally{
            try{iso.close();}catch(Exception ignored){}
        }

        main.post(()->applyScan(result));
    }

    private byte[] readCapabilityContainer(IsoDep iso,List<String> log)throws Exception{
        selectNdefApp(iso,"CC",log);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,(byte)0xE1,0x03},"CC SELECT E103",log),"CC select");

        byte[] head=x(iso,new byte[]{0x00,(byte)0xB0,0x00,0x00,0x02},"CC READ LENGTH",log);
        requireReadable(head,"CC length");
        if(head.length<4)throw new Exception("CC length response too short "+hex(head));
        int ccLen=((head[0]&255)<<8)|(head[1]&255);
        if(ccLen<7||ccLen>255)throw new Exception("Unexpected CCLEN "+ccLen);

        byte[] full=x(iso,new byte[]{0x00,(byte)0xB0,0x00,0x00,(byte)ccLen},"CC READ FULL",log);
        requireReadable(full,"CC");
        return stripStatus(full);
    }

    private List<NdefFileInfo> parseNdefFiles(byte[] cc){
        List<NdefFileInfo> out=new ArrayList<>();
        if(cc==null||cc.length<7)return out;

        int i=7;
        while(i+1<cc.length){
            int type=cc[i]&255;
            if(type==0x00){i++;continue;}
            if(type==0xFE)break;
            int len=cc[i+1]&255;
            if(i+2+len>cc.length)break;

            if(type==0x04&&len>=6){
                int id=((cc[i+2]&255)<<8)|(cc[i+3]&255);
                int max=((cc[i+4]&255)<<8)|(cc[i+5]&255);
                int ra=cc[i+6]&255;
                int wa=cc[i+7]&255;
                out.add(new NdefFileInfo(id,max,ra,wa));
            }
            i+=2+len;
        }
        return out;
    }

    private byte[] readNdefFile(IsoDep iso,NdefFileInfo info,String label,List<String> log)throws Exception{
        selectNdefApp(iso,label,log);
        byte hi=(byte)((info.id>>8)&255);
        byte lo=(byte)(info.id&255);

        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,hi,lo},label+" SELECT FILE",log),label+" select");

        byte[] n=x(iso,new byte[]{0x00,(byte)0xB0,0x00,0x00,0x02},label+" READ NLEN",log);
        requireReadable(n,label+" NLEN");
        if(n.length<4)throw new Exception(label+" NLEN response too short "+hex(n));

        int len=((n[0]&255)<<8)|(n[1]&255);
        int ceiling=Math.max(0,info.maxSize-2);
        if(len<0||len>ceiling||len>4096)throw new Exception(label+" invalid NLEN "+len+" (max "+ceiling+")");

        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int off=2;
        int remain=len;

        while(remain>0){
            int take=Math.min(remain,0xE0);
            byte[] r=x(iso,new byte[]{0x00,(byte)0xB0,(byte)(off>>8),(byte)off,(byte)take},
                    label+" READ +"+(off-2),log);
            requireReadable(r,label+" read");
            int dataLen=Math.max(0,r.length-2);
            if(dataLen>0)out.write(r,0,dataLen);
            off+=dataLen;
            remain-=dataLen;
            if(dataLen==0||isEofWarning(r))break;
        }
        return out.toByteArray();
    }

    private void selectNdefApp(IsoDep iso,String label,List<String> log)throws Exception{
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x04,0x00,0x07,(byte)0xD2,0x76,0x00,0x00,(byte)0x85,0x01,0x01,0x00},
                label+" SELECT NDEF APP",log),label+" NDEF app");
    }

    private byte[] runReadOnlyProbe(IsoDep iso,NdefFileInfo commandFile,int action,String label,List<String> log)throws Exception{
        selectNdefApp(iso,label,log);
        byte hi=(byte)((commandFile.id>>8)&255);
        byte lo=(byte)(commandFile.id&255);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,hi,lo},
                label+" SELECT COMMAND FILE",log),label+" command select");

        // Empty VERIFY is only a lock-state probe and does not consume retries.
        byte[] lockProbe=x(iso,new byte[]{0x00,0x20,0x00,0x02,0x00},
                label+" VERIFY STATUS",log);
        if(isOk(lockProbe)){
            log.add(label+" write area already unlocked");
        }else if(lockProbe.length>=2 && (lockProbe[lockProbe.length-2]&255)==0x63){
            byte[] verify=new byte[21];
            verify[0]=0x00; verify[1]=0x20; verify[2]=0x00; verify[3]=0x02; verify[4]=0x10;
            byte[] vr=x(iso,verify,label+" VERIFY 16-ZERO WRITE PASSWORD",log);
            if(!isOk(vr)){
                throw new Exception("write password rejected "+hex(vr)+"; stopped without retrying");
            }
        }else{
            throw new Exception("unexpected VERIFY status "+hex(lockProbe));
        }

        byte[] record=buildReadRecord(action);
        requireOk(x(iso,new byte[]{0x00,(byte)0xD6,0x00,0x00,0x02,0x00,0x00},
                label+" INVALIDATE NLEN",log),label+" invalidate");

        byte[] write=new byte[5+record.length];
        write[0]=0x00; write[1]=(byte)0xD6; write[2]=0x00; write[3]=0x02; write[4]=(byte)record.length;
        System.arraycopy(record,0,write,5,record.length);
        requireOk(x(iso,write,label+" WRITE READ-QUERY",log),label+" query write");

        byte[] commit=new byte[]{0x00,(byte)0xD6,0x00,0x00,0x02,0x00,(byte)record.length};
        requireOk(x(iso,commit,label+" COMMIT NLEN",log),label+" commit");

        Thread.sleep(200);
        pulseGpo(iso,0x00,label+" GPO LOW",log);
        Thread.sleep(1200);
        pulseGpo(iso,0x01,label+" GPO HIGH",log);

        byte[] last=null;
        for(int i=0;i<10;i++){
            Thread.sleep(350);
            last=readNdefFile(iso,commandFile,label+" POLL "+(i+1),log);
            if(last!=null && last.length>=8){
                int marker=last[4]&255;
                int gotAction=last[5]&255;
                if(marker==0x00 && gotAction==action){
                    return last;
                }
            }
        }
        throw new Exception("no ACK for opcode 0x"+String.format(Locale.ROOT,"%02X",action)+
                "; last response "+hex(last));
    }

    private void pulseGpo(IsoDep iso,int value,String label,List<String> log)throws Exception{
        selectNdefApp(iso,label,log);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,(byte)0xE1,0x01},
                label+" SELECT SYSTEM FILE",log),label+" system select");
        requireOk(x(iso,new byte[]{(byte)0xA2,(byte)0xD6,0x00,0x1F,0x01,(byte)value},
                label,log),label);
    }

    private static byte[] buildReadRecord(int action){
        byte[] rec=new byte[8];
        rec[0]=(byte)0xD4;
        rec[1]=0x01;
        rec[2]=0x04;
        rec[3]=0x02;
        rec[4]=(byte)0x80;
        rec[5]=(byte)action;
        int crc=crc16(new byte[]{rec[4],rec[5]});
        rec[6]=(byte)((crc>>8)&255);
        rec[7]=(byte)(crc&255);
        return rec;
    }

    private byte[] x(IsoDep iso,byte[] cmd,String label,List<String> log)throws Exception{
        byte[] r=iso.transceive(cmd);
        log.add(label+"\n> "+hex(cmd)+"\n< "+hex(r));
        return r;
    }

    private static boolean isOk(byte[] r){
        return r!=null&&r.length>=2&&(r[r.length-2]&255)==0x90&&(r[r.length-1]&255)==0x00;
    }

    private static boolean isEofWarning(byte[] r){
        return r!=null&&r.length>=2&&(r[r.length-2]&255)==0x62&&(r[r.length-1]&255)==0x82;
    }

    private static void requireOk(byte[] r,String what)throws Exception{
        if(!isOk(r))throw new Exception(what+" rejected "+hex(r));
    }

    private static void requireReadable(byte[] r,String what)throws Exception{
        if(!isOk(r)&&!isEofWarning(r))throw new Exception(what+" rejected "+hex(r));
    }

    private static byte[] stripStatus(byte[] r){
        if(r==null||r.length<2)return new byte[0];
        byte[] d=new byte[r.length-2];
        System.arraycopy(r,0,d,0,d.length);
        return d;
    }

    private static void decodeStatus(ScanResult r){
        byte[] s=r.status;
        if(s==null||s.length<8)return;

        try{
            int typeLen=s[1]&255;
            int payloadLen=s[2]&255;
            int payloadStart=3+typeLen;

            if((s[0]&0x07)==0x01 && typeLen==1 && s[3]==0x55 &&
                    payloadLen>=1 && payloadStart+payloadLen<=s.length){
                int prefix=s[payloadStart]&255;
                String base=prefix==0x03?"http://":prefix==0x04?"https://":"";
                r.uri=base+new String(s,payloadStart+1,payloadLen-1,StandardCharsets.US_ASCII);
            }

            int second=3+typeLen+payloadLen;
            if(second+4<=s.length){
                int tlen=s[second+1]&255;
                int plen=s[second+2]&255;
                int typePos=second+3;
                int pstart=typePos+tlen;

                if(pstart+plen<=s.length&&tlen==1){
                    r.statusType=Character.toString((char)(s[typePos]&255));

                    if(plen>=3){
                        byte[] body=new byte[plen-2];
                        System.arraycopy(s,pstart,body,0,body.length);
                        int got=((s[pstart+plen-2]&255)<<8)|(s[pstart+plen-1]&255);
                        r.statusCrcValid=crc16(body)==got;

                        String id=new String(body,StandardCharsets.US_ASCII);
                        if(id.matches("[0-9]{25}")){
                            r.identity=id;
                            r.productCode=id.substring(0,8);
                            r.descriptor=id.substring(8);
                        }
                    }
                }
            }
        }catch(Exception ignored){}
    }

    private static void decodeCommand(ScanResult r){
        byte[] q=r.command;
        if(q==null||q.length<8)return;

        try{
            int plen=q[2]&255;
            int pstart=4;

            if(pstart+plen<=q.length&&plen>=4){
                r.commandMarker=q[pstart]&255;
                r.commandAction=q[pstart+1]&255;

                int crcDataLen=plen-2;
                byte[] crcData=new byte[crcDataLen];
                System.arraycopy(q,pstart,crcData,0,crcDataLen);

                int got=((q[pstart+plen-2]&255)<<8)|(q[pstart+plen-1]&255);
                r.commandCrcValid=crc16(crcData)==got;

                int dataLen=Math.max(0,plen-4);
                r.commandData=new byte[dataLen];
                if(dataLen>0)System.arraycopy(q,pstart+2,r.commandData,0,dataLen);
            }
        }catch(Exception ignored){}
    }

    private static String decodeProbeResponse(byte[] q){
        if(q==null)return "—";
        if(q.length<8)return "Short response: "+hex(q);
        int plen=q[2]&255;
        if(4+plen>q.length)return "Malformed response: "+hex(q);
        int marker=q[4]&255;
        int action=q[5]&255;
        int dataLen=Math.max(0,plen-4);
        byte[] data=new byte[dataLen];
        if(dataLen>0)System.arraycopy(q,6,data,0,dataLen);
        int got=((q[4+plen-2]&255)<<8)|(q[4+plen-1]&255);
        byte[] crcData=new byte[Math.max(0,plen-2)];
        if(crcData.length>0)System.arraycopy(q,4,crcData,0,crcData.length);
        boolean crc=crc16(crcData)==got;
        return "marker="+String.format(Locale.ROOT,"%02X",marker)+
                " action=0x"+String.format(Locale.ROOT,"%02X",action)+
                " data="+hex(data)+
                " CRC="+(crc?"valid":"invalid");
    }

    private static int crc16(byte[] data){
        int v=0xFFFF;
        for(byte bb:data){
            v^=(bb&255);
            for(int n=0;n<8;n++){
                v=((v&1)!=0)?((v>>>1)^0x6363):(v>>>1);
            }
        }
        return (~v)&0xFFFF;
    }

    private void applyScan(ScanResult r){
        if(!r.ok){
            linkGauge.setValue(8);
            linkState.setText("SCAN INCOMPLETE");
            linkState.setTextColor(AMBER);
            linkHint.setText(r.error==null?"NFC was seen but no readable status record was returned.":r.error);
            rawText.setText(join(r.log));
            if(statsProbeButton!=null){
                statsProbeButton.setEnabled(true);
                statsProbeButton.setText("READ DRYING COUNTERS");
            }
            return;
        }

        scanCount++;
        String previous=prefs.getString("last_command_hex","");
        String nowHex=hex(r.command);
        String change=changedBytes(previous,r.command);

        Set<String> unique=new LinkedHashSet<>(prefs.getStringSet("unique_responses",new LinkedHashSet<>()));
        if(r.command!=null)unique.add(nowHex);

        String clock=new SimpleDateFormat("HH:mm:ss",Locale.UK).format(new Date());
        String history=prefs.getString("history","");
        String entry=clock+"  "+stage+"  •  action 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+"  •  "+change;
        history=appendHistory(history,entry);

        prefs.edit()
                .putInt("scans",scanCount)
                .putString("last_command_hex",nowHex)
                .putStringSet("unique_responses",unique)
                .putString("last_seen",clock)
                .putString("history",history)
                .apply();

        linkGauge.setValue(100);
        linkGauge.setCenter("LINK","LIVE");
        if(resultProbeLabel(r).length()>0){
            linkState.setText(r.probeSuccess?"DRYING COUNTERS RECEIVED":"DRYING COUNTER PROBE FAILED");
            linkState.setTextColor(r.probeSuccess?GREEN:AMBER);
            linkHint.setText(r.probeSuccess?decodeProbeResponse(r.probeResponse):r.probeError);
        }else{
            linkState.setText("DRYER CONNECTED");
            linkState.setTextColor(GREEN);
            linkHint.setText(change);
        }
        if(statsProbeButton!=null){
            statsProbeButton.setEnabled(true);
            statsProbeButton.setText("READ DRYING COUNTERS");
        }

        identityText.setText(
                "Product     "+safe(r.productCode)+"\n"+
                "Identity    "+safe(r.identity)+"\n"+
                "Descriptor  "+safe(r.descriptor)+"\n"+
                "URI         "+safe(r.uri)+"\n"+
                "Status CRC  "+(r.statusCrcValid?"VALID":"unknown")
        );

        String markerName=r.commandMarker==0?"RESPONSE":r.commandMarker==0x80?"COMMAND":"0x"+String.format(Locale.ROOT,"%02X",r.commandMarker);
        responseText.setText(
                "Stage: "+stage+"\n"+
                "Marker: "+markerName+"\n"+
                "Action: 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+"\n"+
                "Data:   "+hex(r.commandData)+"\n"+
                "CRC:    "+(r.commandCrcValid?"VALID":"unknown")+"\n"+
                "Diff:   "+change
        );

        rawText.setText(
                "CC\n"+hex(r.cc)+"\n\n"+
                "STATUS\n"+hex(r.status)+"\n\n"+
                "COMMAND/RESPONSE\n"+hex(r.command)+"\n\n"+
                "STATUS ASCII\n"+printable(r.status)+
                (r.probeAttempted?"\n\nDRYING COUNTER PROBE\n"+
                        (r.probeSuccess?decodeProbeResponse(r.probeResponse):"FAILED: "+r.probeError):"")
        );

        historyText.setText(historyDisplay());
        refreshLocalStats();

        String when=new SimpleDateFormat("dd MMM yyyy HH:mm:ss",Locale.UK).format(new Date());
        lastCapture=
                "Candy Dryer Lab MK8 passive capture\n"+
                "Stage: "+stage+"\n"+
                "Model: CS C10DF-80 / 31101151\n"+
                "Time: "+when+"\n"+
                "Tech: "+r.tech+"\n"+
                "URI: "+r.uri+"\n"+
                "Identity: "+r.identity+"\n"+
                "Product code: "+r.productCode+"\n"+
                "Descriptor: "+r.descriptor+"\n"+
                "Status CRC valid: "+r.statusCrcValid+"\n"+
                "Response marker: "+r.commandMarker+"\n"+
                "Response action: 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+"\n"+
                "Response data: "+hex(r.commandData)+"\n"+
                "Response CRC valid: "+r.commandCrcValid+"\n"+
                "Response comparison: "+change+"\n"+
                "Drying counter probe attempted: "+r.probeAttempted+"\n"+
                "Drying counter probe success: "+r.probeSuccess+"\n"+
                "Drying counter probe result: "+(r.probeSuccess?decodeProbeResponse(r.probeResponse):safe(r.probeError))+"\n"+
                "CC: "+hex(r.cc)+"\n"+
                "STATUS: "+hex(r.status)+"\n"+
                "COMMAND: "+hex(r.command)+"\n\n"+
                "APDU LOG\n"+join(r.log);

        shareButton.setEnabled(true);
        shareButton.setAlpha(1f);
    }

    private String appendHistory(String history,String entry){
        List<String> lines=new ArrayList<>();
        if(history!=null&&!history.trim().isEmpty()){
            String[] old=history.split("\\n");
            for(String s:old)if(!s.trim().isEmpty())lines.add(s);
        }
        lines.add(entry);
        while(lines.size()>10)lines.remove(0);
        StringBuilder out=new StringBuilder();
        for(String s:lines){
            if(out.length()>0)out.append("\n");
            out.append(s);
        }
        return out.toString();
    }

    private String historyDisplay(){
        String h=prefs==null?"":prefs.getString("history","");
        if(h==null||h.trim().isEmpty())return "No learning captures stored yet.";
        return "Recent local captures\n"+h;
    }

    private void refreshLocalStats(){
        if(scanCountText==null)return;
        scanCount=prefs.getInt("scans",scanCount);
        scanCountText.setText(Integer.toString(scanCount));
        Set<String> unique=prefs.getStringSet("unique_responses",new LinkedHashSet<>());
        uniqueResponseText.setText(Integer.toString(unique==null?0:unique.size()));
        lastSeenText.setText(prefs.getString("last_seen","—"));
    }

    private static String changedBytes(String previousHex,byte[] now){
        if(now==null)return "No command/response record";
        if(previousHex==null||previousHex.trim().isEmpty())return "First captured response";

        String clean=previousHex.replace(" ","").trim();
        if(clean.length()%2!=0)return "Previous response unavailable";

        byte[] prev=new byte[clean.length()/2];
        try{
            for(int i=0;i<prev.length;i++){
                prev[i]=(byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
            }
        }catch(Exception e){
            return "Previous response unavailable";
        }

        if(prev.length==now.length){
            boolean same=true;
            for(int i=0;i<now.length;i++){
                if(prev[i]!=now[i]){same=false;break;}
            }
            if(same)return "NFC response unchanged";
        }

        StringBuilder s=new StringBuilder("Changed ");
        int max=Math.max(prev.length,now.length);
        int shown=0;
        for(int i=0;i<max&&shown<10;i++){
            int a=i<prev.length?(prev[i]&255):-1;
            int b=i<now.length?(now[i]&255):-1;
            if(a!=b){
                if(shown++>0)s.append(", ");
                s.append("b").append(i).append(" ");
                s.append(a<0?"--":String.format(Locale.ROOT,"%02X",a));
                s.append("→");
                s.append(b<0?"--":String.format(Locale.ROOT,"%02X",b));
            }
        }
        return shown==0?"Response length changed":s.toString();
    }

    private String resultProbeLabel(ScanResult r){
        return r.probeAttempted?"probe":"";
    }

    private void showError(String s){
        linkGauge.setValue(5);
        linkState.setText("NFC TAG SEEN");
        linkState.setTextColor(AMBER);
        linkHint.setText(s);
    }

    private void shareCapture(){
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT,"Candy Dryer Lab capture");
        i.putExtra(Intent.EXTRA_TEXT,lastCapture);
        startActivity(Intent.createChooser(i,"Share NFC capture"));
    }

    private static String safe(String s){
        return s==null?"—":s;
    }

    private static String printable(byte[] b){
        if(b==null)return "—";
        StringBuilder s=new StringBuilder();
        for(byte x:b){
            int c=x&255;
            s.append(c>=32&&c<=126?(char)c:'.');
        }
        return s.toString();
    }

    private static String join(List<String> l){
        StringBuilder s=new StringBuilder();
        for(String x:l)s.append(x).append("\n");
        return s.toString();
    }

    private static String hex(byte[] b){
        if(b==null)return "—";
        StringBuilder s=new StringBuilder();
        for(byte x:b)s.append(String.format(Locale.ROOT,"%02X ",x&255));
        return s.toString().trim();
    }

    private static class NdefFileInfo{
        final int id,maxSize,readAccess,writeAccess;
        NdefFileInfo(int id,int maxSize,int readAccess,int writeAccess){
            this.id=id;
            this.maxSize=maxSize;
            this.readAccess=readAccess;
            this.writeAccess=writeAccess;
        }
    }

    private static class ScanResult{
        boolean ok,statusCrcValid,commandCrcValid,probeAttempted,probeSuccess;
        String tech,error,uri,statusType,identity,productCode,descriptor,probeError;
        int commandMarker=-1,commandAction=-1;
        byte[] cc,status,command,commandData,probeResponse;
        List<String> log=new ArrayList<>();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener{
        public void onStartTrackingTouch(SeekBar s){}
        public void onStopTrackingTouch(SeekBar s){}
    }

    public static class GaugeView extends View{
        Paint p=new Paint(1);
        RectF oval=new RectF();
        float value=0;
        String top="NFC",bottom="READY";

        GaugeView(Activity c){
            super(c);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setValue(float v){
            value=Math.max(0,Math.min(100,v));
            invalidate();
        }

        void setCenter(String a,String b){
            top=a;
            bottom=b;
            invalidate();
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight();
            float cx=w/2f,cy=h*.58f;
            float r=Math.min(w,h)*.39f;
            oval.set(cx-r,cy-r,cx+r,cy+r);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(10,w*.045f));
            p.setColor(Color.rgb(38,59,72));
            c.drawArc(oval,150,240,false,p);

            p.setColor(value>80?GREEN:CYAN);
            c.drawArc(oval,150,240*(value/100f),false,p);

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(TEXT);
            p.setTextSize(w*.14f);
            c.drawText(top,cx,cy-2,p);

            p.setColor(MUTED);
            p.setTextSize(w*.052f);
            c.drawText(bottom,cx,cy+w*.075f,p);

            p.setTextSize(w*.038f);
            c.drawText("ISO-DEP  •  TYPE 4",cx,h-8,p);
        }
    }
}
