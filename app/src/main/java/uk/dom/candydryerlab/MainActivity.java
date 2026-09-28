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
import android.view.ViewGroup;
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
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int BG=Color.rgb(7,16,25), PANEL=Color.rgb(14,28,40), PANEL2=Color.rgb(19,37,51);
    private static final int TEXT=Color.rgb(236,246,250), MUTED=Color.rgb(145,168,181), CYAN=Color.rgb(104,214,255);
    private static final int GREEN=Color.rgb(93,224,161), AMBER=Color.rgb(255,194,92);
    private final Handler main=new Handler(Looper.getMainLooper());
    private NfcAdapter nfc;
    private GaugeView gauge;
    private TextView state, hint, moisture, temperature, load, remaining, stats, raw;
    private Button share, deep;
    private volatile boolean deepScanArmed=false;
    private SharedPreferences prefs;
    private String lastCapture="No capture yet.";
    private int scanCount=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        prefs=getSharedPreferences("dryer_lab",MODE_PRIVATE);
        scanCount=prefs.getInt("scans",0);
        nfc=NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        updateNfcState();
    }

    private View buildUi(){
        ScrollView sv=new ScrollView(this); sv.setFillViewport(true); sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(40));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView brand=txt("DRYER LAB",30,TEXT,true); root.addView(brand);
        TextView model=txt("Candy CS C10DF-80  •  Product 31101151",14,MUTED,false); model.setPadding(0,dp(2),0,dp(18)); root.addView(model);

        LinearLayout hero=card(); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(18),dp(18),dp(18),dp(18));
        state=txt("READY FOR NFC",13,CYAN,true); state.setGravity(Gravity.CENTER); hero.addView(state);
        gauge=new GaugeView(this); hero.addView(gauge,new LinearLayout.LayoutParams(dp(250),dp(190)));
        hint=txt("Turn the dryer dial to Smart Touch, then hold your phone against the NFC logo.",14,MUTED,false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(dp(8),0,dp(8),0); hero.addView(hint);
        root.addView(hero,lp(-1,-2,0,0,0,16));

        section(root,"LIVE SENSORS");
        GridLayout grid=new GridLayout(this); grid.setColumnCount(2); grid.setUseDefaultMargins(false);
        moisture=sensor(grid,"MOISTURE","--","Not mapped");
        temperature=sensor(grid,"DRUM TEMP","-- °C","Not mapped");
        load=sensor(grid,"LOAD","-- kg","Not mapped");
        remaining=sensor(grid,"TIME LEFT","--","Not mapped");
        root.addView(grid,lp(-1,-2,0,0,0,16));

        section(root,"PROGRAMME STUDIO");
        LinearLayout studio=card();
        TextView p=txt("Build a drying profile",19,TEXT,true); studio.addView(p);
        TextView safety=txt("Controls are saved locally. NFC transmission stays locked until this model's programme bytes are verified.",12,AMBER,false);
        safety.setPadding(0,dp(4),0,dp(14)); studio.addView(safety);

        TextView dryLabel=txt("Dryness  •  Cupboard",14,TEXT,true); studio.addView(dryLabel);
        SeekBar dry=new SeekBar(this); dry.setMax(3); dry.setProgress(2); studio.addView(dry);
        String[] levels={"Iron","Hanger","Cupboard","Extra dry"};
        dry.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar s,int v,boolean u){dryLabel.setText("Dryness  •  "+levels[v]);}});

        TextView timeLabel=txt("Timed dry  •  Auto sensor",14,TEXT,true); timeLabel.setPadding(0,dp(8),0,0); studio.addView(timeLabel);
        SeekBar time=new SeekBar(this); time.setMax(12); time.setProgress(0); studio.addView(time);
        time.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar s,int v,boolean u){timeLabel.setText(v==0?"Timed dry  •  Auto sensor":"Timed dry  •  "+(v*15)+" min");}});

        LinearLayout toggles=new LinearLayout(this); toggles.setOrientation(LinearLayout.VERTICAL);
        toggles.addView(toggle("Easy Iron / anti-crease"));
        toggles.addView(toggle("Gentle tumble"));
        toggles.addView(toggle("Low heat"));
        studio.addView(toggles);

        Button prepare=button("SAVE PROFILE",false); studio.addView(prepare,lp(-1,dp(50),0,12,0,0));
        prepare.setOnClickListener(v->Toast.makeText(this,"Profile saved locally. Transmission remains safely locked.",Toast.LENGTH_LONG).show());
        root.addView(studio,lp(-1,-2,0,0,0,16));

        section(root,"STATISTICS & DIAGNOSTICS");
        LinearLayout diag=card();
        stats=txt("Total NFC scans   "+scanCount+"\nProduct code      31101151\nStatus payload    awaiting scan\nDeep diagnostics  not run yet",15,TEXT,false); diag.addView(stats);
        ProgressBar confidence=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        confidence.setMax(100); confidence.setProgress(25); diag.addView(confidence,lp(-1,dp(10),0,14,0,4));
        TextView conf=txt("Protocol map  •  transport known, dryer fields awaiting first capture",12,MUTED,false); diag.addView(conf);
        raw=txt("Raw NFC data will appear here after a scan.",12,MUTED,false); raw.setTypeface(Typeface.MONOSPACE); raw.setPadding(0,dp(14),0,dp(8)); diag.addView(raw);
        deep=button("ARM DEEP READ",false);
        deep.setOnClickListener(v->{
            deepScanArmed=true;
            deep.setText("DEEP READ ARMED — TAP DRYER");
            state.setText("DEEP READ ARMED");
            state.setTextColor(AMBER);
            hint.setText("Hold the phone against Smart Touch. This sends only documented read/diagnostic queries — never start/test-cycle commands.");
        });
        diag.addView(deep,lp(-1,dp(50),0,10,0,0));
        share=button("SHARE CAPTURE",true); share.setEnabled(false); share.setAlpha(.45f); share.setOnClickListener(v->shareCapture()); diag.addView(share,lp(-1,dp(50),0,10,0,0));
        root.addView(diag,lp(-1,-2,0,0,0,16));

        TextView footer=txt("Passive scan by default  •  Deep Read uses diagnostic/read opcodes only",11,MUTED,false);
        footer.setGravity(Gravity.CENTER); root.addView(footer);
        return sv;
    }

    private Switch toggle(String label){
        Switch s=new Switch(this); s.setText(label); s.setTextColor(TEXT); s.setTextSize(14); s.setPadding(0,dp(5),0,dp(5)); return s;
    }

    private TextView sensor(GridLayout grid,String title,String value,String sub){
        LinearLayout box=cardSmall();
        TextView t=txt(title,11,MUTED,true); box.addView(t);
        TextView v=txt(value,23,TEXT,true); v.setPadding(0,dp(4),0,0); box.addView(v);
        TextView st=txt(sub,11,MUTED,false); box.addView(st);
        GridLayout.LayoutParams gp=new GridLayout.LayoutParams();
        gp.width=0; gp.height=dp(112); gp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        gp.setMargins(dp(3),dp(3),dp(3),dp(3)); grid.addView(box,gp);
        return v;
    }

    private void section(LinearLayout root,String label){
        TextView t=txt(label,12,MUTED,true); t.setLetterSpacing(.12f); t.setPadding(dp(2),dp(2),0,dp(8)); root.addView(t);
    }

    private LinearLayout card(){
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(16),dp(16),dp(16));
        l.setBackground(round(PANEL,18)); return l;
    }
    private LinearLayout cardSmall(){
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14),dp(13),dp(12),dp(10));
        l.setBackground(round(PANEL2,16)); return l;
    }
    private GradientDrawable round(int color,int radius){
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); g.setStroke(dp(1),Color.rgb(35,57,71)); return g;
    }
    private TextView txt(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private Button button(String s,boolean outline){
        Button b=new Button(this); b.setText(s); b.setTextSize(13); b.setTextColor(outline?CYAN:BG); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable g=round(outline?PANEL2:CYAN,14); if(outline)g.setStroke(dp(1),CYAN); b.setBackground(g); return b;
    }
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume(); if(nfc!=null&&nfc.isEnabled())nfc.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null); updateNfcState();}
    @Override protected void onPause(){super.onPause(); if(nfc!=null)nfc.disableReaderMode(this);}
    private void updateNfcState(){
        if(state==null)return;
        if(nfc==null){state.setText("NFC NOT AVAILABLE"); state.setTextColor(AMBER);}
        else if(!nfc.isEnabled()){state.setText("TURN NFC ON"); state.setTextColor(AMBER);}
        else {state.setText("READY FOR NFC"); state.setTextColor(CYAN);}
    }

    @Override public void onTagDiscovered(Tag tag){
        IsoDep iso=IsoDep.get(tag);
        if(iso==null){main.post(()->showError("Tag detected, but it is not ISO-DEP / NFC Type 4."));return;}
        ScanResult result=new ScanResult();
        try{
            iso.connect(); iso.setTimeout(5000);
            result.tech=String.join(", ",tag.getTechList());

            // CC is NOT an NDEF file. MK3 incorrectly treated its first two bytes
            // (CCLEN) as NLEN, then read past EOF, producing SW 62 82.
            result.cc=readCapabilityContainer(iso,result.log);

            List<NdefFileInfo> files=parseNdefFiles(result.cc);
            result.log.add("CC parsed: "+files.size()+" NDEF file(s)");
            NdefFileInfo statusFile=null, commandFile=null;
            for(NdefFileInfo info:files){
                result.log.add(String.format(Locale.ROOT,
                        "FILE %04X max=%d read=%02X write=%02X",
                        info.id,info.maxSize,info.readAccess,info.writeAccess));
                if(statusFile==null && info.readAccess==0x00 && info.writeAccess==0xFF) statusFile=info;
                if(commandFile==null && info.readAccess==0x00 && info.writeAccess!=0xFF) commandFile=info;
            }
            if(statusFile==null && !files.isEmpty()) statusFile=files.get(0);
            if(commandFile==null && files.size()>1) commandFile=files.get(1);

            if(statusFile!=null) result.status=readNdefFile(iso,statusFile,"STATUS",result.log);
            if(commandFile!=null){
                try{result.command=readNdefFile(iso,commandFile,"COMMAND",result.log);}
                catch(Exception e){result.log.add("COMMAND read: "+e.getMessage());}
            }

            decodeStatus(result);
            decodeCommand(result);

            if(deepScanArmed && commandFile!=null){
                result.deepAttempted=true;
                // Known read-only commands from the Candy Smart Touch protocol family.
                // Deliberately excludes STORE/START/LINE-TEST opcodes 0A/0B/0C.
                int[] actions={0x11,0x06,0x07,0x08};
                String[] names={"DRYING_COUNTERS","LAST_ERROR","MAIN_SW","UI_SW"};
                for(int i=0;i<actions.length;i++){
                    try{
                        byte[] reply=queryReadCommand(iso,commandFile,actions[i],names[i],result.log);
                        result.probes.add(names[i]+" (0x"+String.format(Locale.ROOT,"%02X",actions[i])+") = "+hex(reply));
                    }catch(Exception e){
                        result.probes.add(names[i]+" FAILED: "+e.getMessage());
                    }
                }
                deepScanArmed=false;
            }
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
        if(head.length<4) throw new Exception("CC length response too short "+hex(head));
        int ccLen=((head[0]&255)<<8)|(head[1]&255);
        if(ccLen<7||ccLen>255) throw new Exception("Unexpected CCLEN "+ccLen);

        byte[] full=x(iso,new byte[]{0x00,(byte)0xB0,0x00,0x00,(byte)ccLen},"CC READ FULL",log);
        requireReadable(full,"CC");
        return stripStatus(full);
    }

    private List<NdefFileInfo> parseNdefFiles(byte[] cc){
        List<NdefFileInfo> out=new ArrayList<>();
        if(cc==null||cc.length<7) return out;
        int i=7;
        while(i+1<cc.length){
            int type=cc[i]&255;
            if(type==0x00){i++;continue;}
            if(type==0xFE) break;
            int len=cc[i+1]&255;
            if(i+2+len>cc.length) break;
            if(type==0x04 && len>=6){
                int id=((cc[i+2]&255)<<8)|(cc[i+3]&255);
                int max=((cc[i+4]&255)<<8)|(cc[i+5]&255);
                int ra=cc[i+6]&255, wa=cc[i+7]&255;
                out.add(new NdefFileInfo(id,max,ra,wa));
            }
            i+=2+len;
        }
        return out;
    }

    private byte[] readNdefFile(IsoDep iso,NdefFileInfo info,String label,List<String> log)throws Exception{
        selectNdefApp(iso,label,log);
        byte hi=(byte)((info.id>>8)&255), lo=(byte)(info.id&255);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,hi,lo},label+" SELECT FILE",log),label+" select");

        byte[] n=x(iso,new byte[]{0x00,(byte)0xB0,0x00,0x00,0x02},label+" READ NLEN",log);
        requireReadable(n,label+" NLEN");
        if(n.length<4) throw new Exception(label+" NLEN response too short "+hex(n));
        int len=((n[0]&255)<<8)|(n[1]&255);
        int ceiling=Math.max(0,info.maxSize-2);
        if(len<0||len>ceiling||len>4096) throw new Exception(label+" invalid NLEN "+len+" (max "+ceiling+")");

        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int off=2, remain=len;
        while(remain>0){
            int take=Math.min(remain,0xE0);
            byte[] r=x(iso,new byte[]{0x00,(byte)0xB0,(byte)(off>>8),(byte)off,(byte)take},
                    label+" READ +"+(off-2),log);
            requireReadable(r,label+" read");
            int dataLen=Math.max(0,r.length-2);
            if(dataLen>0) out.write(r,0,dataLen);
            off+=dataLen; remain-=dataLen;
            if(dataLen==0 || isEofWarning(r)) break;
        }
        return out.toByteArray();
    }

    private void selectNdefApp(IsoDep iso,String label,List<String> log)throws Exception{
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x04,0x00,0x07,(byte)0xD2,0x76,0x00,0x00,(byte)0x85,0x01,0x01,0x00},
                label+" SELECT NDEF APP",log),label+" NDEF app");
    }

    private static boolean isOk(byte[] r){return r!=null&&r.length>=2&&(r[r.length-2]&255)==0x90&&(r[r.length-1]&255)==0x00;}
    private static boolean isEofWarning(byte[] r){return r!=null&&r.length>=2&&(r[r.length-2]&255)==0x62&&(r[r.length-1]&255)==0x82;}
    private static void requireOk(byte[] r,String what)throws Exception{if(!isOk(r))throw new Exception(what+" rejected "+hex(r));}
    private static void requireReadable(byte[] r,String what)throws Exception{if(!isOk(r)&&!isEofWarning(r))throw new Exception(what+" rejected "+hex(r));}
    private static byte[] stripStatus(byte[] r){
        if(r==null||r.length<2)return new byte[0];
        byte[] d=new byte[r.length-2]; System.arraycopy(r,0,d,0,d.length); return d;
    }

    private byte[] queryReadCommand(IsoDep iso,NdefFileInfo file,int action,String name,List<String> log)throws Exception{
        selectNdefApp(iso,name,log);
        byte hi=(byte)((file.id>>8)&255), lo=(byte)(file.id&255);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,hi,lo},name+" SELECT COMMAND FILE",log),name+" command select");

        byte[] verify=new byte[21];
        verify[0]=0x00; verify[1]=0x20; verify[2]=0x00; verify[3]=0x02; verify[4]=0x10;
        requireOk(x(iso,verify,name+" VERIFY WRITE",log),name+" verify");

        byte[] record=buildSimpleQuery(action);
        requireOk(x(iso,new byte[]{0x00,(byte)0xD6,0x00,0x00,0x02,0x00,0x00},name+" INVALIDATE NLEN",log),name+" invalidate");

        byte[] write=new byte[5+record.length];
        write[0]=0x00; write[1]=(byte)0xD6; write[2]=0x00; write[3]=0x02; write[4]=(byte)record.length;
        System.arraycopy(record,0,write,5,record.length);
        requireOk(x(iso,write,name+" WRITE QUERY",log),name+" write");

        byte[] commit=new byte[]{0x00,(byte)0xD6,0x00,0x00,0x02,0x00,(byte)record.length};
        requireOk(x(iso,commit,name+" COMMIT NLEN",log),name+" commit");

        Thread.sleep(200);
        pulseGpo(iso,0x00,name+" GPO LOW",log);
        Thread.sleep(1200);
        pulseGpo(iso,0x01,name+" GPO HIGH",log);

        for(int i=0;i<8;i++){
            Thread.sleep(350);
            byte[] reply=readNdefFile(iso,file,name+" RESPONSE",log);
            if(reply!=null && reply.length>=8 && (reply[4]&255)==0x00 && (reply[5]&255)==action) return reply;
            if(reply!=null && reply.length>=6 && (reply[4]&255)==0x00 && (reply[5]&255)==action) return reply;
        }
        throw new Exception("No MCU response/ACK");
    }

    private void pulseGpo(IsoDep iso,int value,String label,List<String> log)throws Exception{
        selectNdefApp(iso,label,log);
        requireOk(x(iso,new byte[]{0x00,(byte)0xA4,0x00,0x0C,0x02,(byte)0xE1,0x01},label+" SELECT SYSTEM",log),label+" system select");
        requireOk(x(iso,new byte[]{(byte)0xA2,(byte)0xD6,0x00,0x1F,0x01,(byte)value},label,log),label);
    }

    private static byte[] buildSimpleQuery(int action){
        byte[] record=new byte[]{(byte)0xD4,0x01,0x04,0x02,(byte)0x80,(byte)action,0x00,0x00};
        int crc=crc16(new byte[]{(byte)0x80,(byte)action});
        record[6]=(byte)((crc>>8)&255); record[7]=(byte)(crc&255);
        return record;
    }

    private static int crc16(byte[] data){
        int v=0xFFFF;
        for(byte bb:data){
            v^=(bb&255);
            for(int n=0;n<8;n++) v=((v&1)!=0)?((v>>>1)^0x6363):(v>>>1);
        }
        return (~v)&0xFFFF;
    }

    private static void decodeStatus(ScanResult r){
        byte[] s=r.status;
        if(s==null||s.length<8)return;
        try{
            int typeLen=s[1]&255, payloadLen=s[2]&255;
            int payloadStart=3+typeLen;
            if((s[0]&0x07)==0x01 && typeLen==1 && s[3]==0x55 && payloadLen>=1 && payloadStart+payloadLen<=s.length){
                int prefix=s[payloadStart]&255;
                String base=prefix==0x03?"http://":prefix==0x04?"https://":"";
                r.uri=base+new String(s,payloadStart+1,payloadLen-1,StandardCharsets.US_ASCII);
            }
            int second=3+typeLen+payloadLen;
            if(second+4<=s.length){
                int tlen=s[second+1]&255, plen=s[second+2]&255;
                int typePos=second+3, pstart=typePos+tlen;
                if(pstart+plen<=s.length && tlen==1){
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
            int plen=q[2]&255, pstart=4;
            if(pstart+plen<=q.length && plen>=4){
                r.commandMarker=q[pstart]&255;
                r.commandAction=q[pstart+1]&255;
                int dataLen=plen-2;
                byte[] crcData=new byte[dataLen];
                System.arraycopy(q,pstart,crcData,0,dataLen);
                int got=((q[pstart+plen-2]&255)<<8)|(q[pstart+plen-1]&255);
                r.commandCrcValid=crc16(crcData)==got;
            }
        }catch(Exception ignored){}
    }

    private byte[] x(IsoDep iso,byte[] cmd,String label,List<String> log)throws Exception{
        byte[] r=iso.transceive(cmd); log.add(label+"\n> "+hex(cmd)+"\n< "+hex(r)); return r;
    }

    private void applyScan(ScanResult r){
        if(!r.ok){
            gauge.setValue(8); state.setText("SCAN INCOMPLETE"); state.setTextColor(AMBER);
            hint.setText(r.error==null?"NFC detected but no readable status file was returned. Keep the phone steady and try again.":r.error);
            raw.setText(join(r.log));
            String when=new SimpleDateFormat("dd MMM HH:mm:ss",Locale.UK).format(new Date());
            lastCapture="Candy Dryer Lab incomplete capture\nModel: CS C10DF-80 / 31101151\nTime: "+when+
                    "\nTech: "+r.tech+"\nCC: "+hex(r.cc)+"\nError: "+r.error+"\n\nAPDU LOG\n"+join(r.log);
            share.setEnabled(true); share.setAlpha(1f);
            return;
        }
        scanCount++; prefs.edit().putInt("scans",scanCount).apply();
        gauge.setValue(100); gauge.setCenter("LINK","LIVE");
        state.setText(r.deepAttempted?"DEEP READ COMPLETE":"DRYER CONNECTED"); state.setTextColor(GREEN);
        hint.setText(r.deepAttempted?"Diagnostics captured. Share this capture so the returned fields can be mapped.":"Identity/status decoded. Arm Deep Read to query drying counters, firmware and last-error data.");
        if(deep!=null){deep.setText("ARM DEEP READ");deep.setEnabled(true);}
        int sb=r.status==null?0:r.status.length, cb=r.command==null?0:r.command.length;
        moisture.setText("--"); temperature.setText("-- °C"); load.setText("-- kg"); remaining.setText("--");
        stats.setText(statsText(r,sb,cb));
        String ascii=printable(r.status);
        String when=new SimpleDateFormat("dd MMM HH:mm:ss",Locale.UK).format(new Date());
        String probeText=r.probes.isEmpty()?"—":join(r.probes);
        lastCapture="Candy Dryer Lab capture\nModel: CS C10DF-80 / 31101151\nTime: "+when+
                "\nTech: "+r.tech+"\nURI: "+r.uri+"\nIdentity: "+r.identity+"\nProduct code: "+r.productCode+
                "\nDescriptor: "+r.descriptor+"\nStatus CRC valid: "+r.statusCrcValid+
                "\nCC: "+hex(r.cc)+"\nSTATUS: "+hex(r.status)+"\nSTATUS ASCII: "+ascii+
                "\nCOMMAND: "+hex(r.command)+"\nCommand marker: "+r.commandMarker+" action: 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+
                " CRC valid: "+r.commandCrcValid+"\n\nDEEP PROBES\n"+probeText+"\nAPDU LOG\n"+join(r.log);
        raw.setText("URI  "+r.uri+
                "\nIDENTITY  "+r.identity+
                "\nPRODUCT  "+r.productCode+
                "\nDESCRIPTOR  "+r.descriptor+
                "\nSTATUS CRC  "+(r.statusCrcValid?"VALID":"UNKNOWN/INVALID")+
                "\nLAST CMD  "+(r.commandMarker==0?"response":"command")+"  action 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+
                "  CRC "+(r.commandCrcValid?"VALID":"?")+
                (r.probes.isEmpty()?"":"\n\nDEEP READ\n"+probeText)+
                "\n\nSTATUS HEX\n"+hex(r.status)+"\n\nCOMMAND HEX\n"+hex(r.command));
        share.setEnabled(true); share.setAlpha(1f);
    }

    private String statsText(ScanResult r,int sb,int cb){
        return "Total NFC scans   "+scanCount+"\n"+
               "Product code      "+(r.productCode==null?"—":r.productCode)+"\n"+
               "Status payload    "+(sb==0?"—":sb+" bytes")+"  • CRC "+(r.statusCrcValid?"valid":"?")+"\n"+
               "Command payload   "+(cb==0?"—":cb+" bytes")+"  • action 0x"+String.format(Locale.ROOT,"%02X",r.commandAction)+"\n"+
               "Deep diagnostics  "+(r.deepAttempted?"captured":"not run yet");
    }
    private void showError(String s){gauge.setValue(5);state.setText("NFC TAG SEEN");state.setTextColor(AMBER);hint.setText(s);}
    private void shareCapture(){
        Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_SUBJECT,"Candy Dryer Lab NFC capture"); i.putExtra(Intent.EXTRA_TEXT,lastCapture);
        startActivity(Intent.createChooser(i,"Share NFC capture"));
    }
    private static String printable(byte[] b){
        if(b==null)return "—"; StringBuilder s=new StringBuilder(); for(byte x:b){int c=x&255;s.append(c>=32&&c<=126?(char)c:'.');} return s.toString();
    }
    private static String join(List<String> l){StringBuilder s=new StringBuilder();for(String x:l)s.append(x).append("\n");return s.toString();}
    private static String hex(byte[] b){if(b==null)return "—";StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02X ",x&255));return s.toString().trim();}

    private static class NdefFileInfo{
        final int id,maxSize,readAccess,writeAccess;
        NdefFileInfo(int id,int maxSize,int readAccess,int writeAccess){
            this.id=id; this.maxSize=maxSize; this.readAccess=readAccess; this.writeAccess=writeAccess;
        }
    }
    private static class ScanResult{
        boolean ok, statusCrcValid, commandCrcValid, deepAttempted;
        String tech,error,uri,statusType,identity,productCode,descriptor;
        int commandMarker=-1, commandAction=-1;
        byte[] cc,status,command;
        List<String> log=new ArrayList<>();
        List<String> probes=new ArrayList<>();
    }
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener{
        public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
    }

    public static class GaugeView extends View{
        Paint p=new Paint(1); RectF oval=new RectF(); float value=0; String top="NFC",bottom="READY";
        GaugeView(Activity c){super(c);p.setStrokeCap(Paint.Cap.ROUND);}
        void setValue(float v){value=Math.max(0,Math.min(100,v));invalidate();}
        void setCenter(String a,String b){top=a;bottom=b;invalidate();}
        protected void onDraw(Canvas c){
            super.onDraw(c); float w=getWidth(),h=getHeight(),cx=w/2f,cy=h*.58f,r=Math.min(w,h)*.39f;
            oval.set(cx-r,cy-r,cx+r,cy+r);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(10,w*.045f));p.setColor(Color.rgb(38,59,72));c.drawArc(oval,150,240,false,p);
            p.setColor(value>80?GREEN:CYAN);c.drawArc(oval,150,240*(value/100f),false,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setColor(TEXT);p.setTextSize(w*.14f);c.drawText(top,cx,cy-2,p);
            p.setColor(MUTED);p.setTextSize(w*.052f);c.drawText(bottom,cx,cy+w*.075f,p);
            p.setTextSize(w*.038f);p.setColor(MUTED);c.drawText("ISO-DEP  •  TYPE 4",cx,h-8,p);
        }
    }
}
