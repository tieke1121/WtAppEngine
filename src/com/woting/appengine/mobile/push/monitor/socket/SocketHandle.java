package com.woting.appengine.mobile.push.monitor.socket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;
import java.util.Map;

import com.spiritdata.framework.util.DateUtils;
import com.spiritdata.framework.util.FileNameUtils;
import com.spiritdata.framework.util.JsonUtils;
import com.spiritdata.framework.util.SequenceUUID;
import com.woting.appengine.common.util.MobileUtils;
import com.woting.appengine.intercom.mem.GroupMemoryManage;
import com.woting.appengine.mobile.mediaflow.mem.TalkMemoryManage;
import com.woting.appengine.mobile.mediaflow.model.TalkSegment;
import com.woting.appengine.mobile.mediaflow.model.WholeTalk;
import com.woting.appengine.mobile.model.MobileKey;
import com.woting.appengine.mobile.push.mem.PushMemoryManage;
import com.woting.appengine.mobile.push.model.Message;

/**
 * 处理Socket的线程，此线程是处理一个客户端连接的基础线程。其中包括一个主线程，两个子线程<br/>
 * [主线程(监控本连接的健康状况)]:<br/>
 * 启动子线程，并监控Socket连接的健康状况<br/>
 * [子线程(处理业务逻辑)]:<br/>
 * 发送|正常消息：对应的消息队列中有内容，就发送<br/>
 * 接收|正常+心跳：判断是否连接正常的逻辑也在这个现成中<br/>
 */
//注意，服务端把心跳的功能去掉了
public class SocketHandle extends Thread {
    //三个业务子线程
//    private SendBeat sendBeat;
    private SendMsg sendMsg;
    private ReceiveMsg receiveMsg;

    //控制信息
    protected SocketMonitorConfig smc=null;//套接字监控配置
    protected volatile Object socketSendLock=new Object();
    protected volatile boolean running=true;
    protected long lastVisitTime=System.currentTimeMillis();

    //数据
    protected Socket socket=null;
    protected String socketDesc;
    protected MobileKey mk=null;

    //内存数据
    private PushMemoryManage pmm=PushMemoryManage.getInstance();

    /*
     * 构造函数，同时线程注册到Map中。
     * @param client 客户端Socket
     */
    public SocketHandle(Socket client, SocketMonitorConfig smc) {
        this.socket=client;
        this.smc=smc;
    }
    /*
     * 主线程
     */
    public void run() {
        socketDesc="Socket["+socket.getRemoteSocketAddress()+",socketKey="+socket.hashCode()+"]";
        System.out.println("<"+(new Date()).toString()+">"+socketDesc+"主线程启动");
        //启动业务处理线程
        this.sendMsg=new SendMsg(socketDesc+"发送消息");
        this.sendMsg.start();
        System.out.println("<"+(new Date()).toString()+">"+socketDesc+"发送消息线程启动");
        this.receiveMsg=new ReceiveMsg(socketDesc+"接收消息");
        this.receiveMsg.start();
        System.out.println("<"+(new Date()).toString()+">"+socketDesc+"接收消息线程启动");

        //主线程
        try {
            while (true) {//有任何一个字线程出问题，则关闭这个连接
                try { sleep(10); } catch (InterruptedException e) {};
                Thread.sleep(this.smc.get_MonitorDelay());
                //判断时间戳，看连接是否还有效
                if (System.currentTimeMillis()-lastVisitTime>this.smc.calculate_TimeOut()) {
                    long t=System.currentTimeMillis();
                    System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"超时关闭");
                    break;
                }
                if (this.sendMsg.isInterrupted||!this.sendMsg.isRunning) {
                    this.receiveMsg._interrupt();
                    break;
                }
                if (this.receiveMsg.isInterrupted||!this.receiveMsg.isRunning) {
                    this.sendMsg._interrupt();
                    break;
                }
            }
        } catch (InterruptedException e) {//有异常就退出
            long t=System.currentTimeMillis();
            System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"主监控线程出现异常:"+e.getMessage());
            e.printStackTrace();
        } finally {//关闭所有子任务进程
            if (this.sendMsg!=null) {try {this.sendMsg._interrupt();} catch(Exception e) {}}
            if (this.receiveMsg!=null) {try {this.receiveMsg._interrupt();} catch(Exception e) {}}
            boolean canClose=false;
            int loopCount=0;
            while(!canClose) {
                loopCount++;
                if (loopCount>this.smc.get_TryDestoryAllCount()) {
                    canClose=true;
                    continue;
                }
                if (/* (this.sendBeat!=null&&!this.sendBeat.isRunning)
                   &&*/(this.sendMsg!=null&&!this.sendMsg.isRunning)
                   &&(this.receiveMsg!=null&&!this.receiveMsg.isRunning) ) {
                    canClose=true;
                    continue;
                }
                try { sleep(10); } catch (InterruptedException e) {};
            }
            try {
                SocketHandle.this.socket.close(); //这是主线程的关键
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                this.sendMsg=null;
                this.receiveMsg=null;
                this.socket=null;
            }
        }
    }

    //=====================================================================================
    /*
     * 发送消息线程
     */
    class SendMsg extends Thread {
        private boolean isInterrupted=false;
        private boolean isRunning=true;
        protected SendMsg(String name) {
            super.setName(name);
        }
        protected void _interrupt(){
            isInterrupted = true;
            this.interrupt();
            super.interrupt();
        }
        public void run() {
            PrintWriter out=null;
            this.isRunning=true;
            try {
                while (pmm.isServerRuning()&&SocketHandle.this.running&&!isInterrupted) {
                    try {
                        try { sleep(10); } catch (InterruptedException e) {};
                        long t=System.currentTimeMillis();
                        //发消息
                        if (SocketHandle.this.mk!=null) {
                            //获得消息
                            Message m=pmm.getSendMessages(mk);
                            String mStr="";
                            if (m!=null) {
                                mStr=m.toJson();
                                //发送消息
                                synchronized(socketSendLock) {
                                    boolean canSend=true;
                                    //判断是否过期的语音包，这个比较特殊
                                    if (m.getMsgBizType().equals("AUDIOFLOW")) {
                                        if (t-m.getSendTime()>60*1000) {
                                            canSend=false;
                                        }
                                    }
                                    if (canSend) {
                                        if (out==null) out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(SocketHandle.this.socket.getOutputStream(), "UTF-8")), true);
                                        out.println(mStr);
                                        out.flush();
                                        try {
                                            pmm.logQueue.add(t+"::send::"+mk.toString()+"::"+mStr);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        if (m.getMsgBizType().equals("AUDIOFLOW")&&m.getCommand().equals("b1")) {//对语音广播包做特殊处理
                                            try {
                                                String talkId=((Map)m.getMsgContent()).get("TalkId")+"";
                                                String seqNum=((Map)m.getMsgContent()).get("SeqNum")+"";
                                                TalkMemoryManage tmm=TalkMemoryManage.getInstance();
                                                WholeTalk wt=tmm.getWholeTalk(talkId);
                                                TalkSegment ts=wt.getTalkData().get(Math.abs(Integer.parseInt(seqNum)));
                                                if (ts.getSendFlagMap().get(mk.toString())!=null) ts.getSendFlagMap().put(mk.toString(), 1);
                                            } catch(Exception e) {e.printStackTrace();}
                                        }
                                    }
                                    try { sleep(10); } catch (InterruptedException e) {};//给10毫秒的延迟
                                }
                            }
                            //发送通知类消息
                            if (mk.isUser()) {
                                Message nm=pmm.getNotifyMessages(mk.getUserId());
                                if (nm!=null) {
                                    mStr=nm.toJson();
                                    nm.setToAddr(MobileUtils.getAddr(mk));
                                    synchronized(socketSendLock) {
                                        if (out==null) out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(SocketHandle.this.socket.getOutputStream(), "UTF-8")), true);
                                        out.println(mStr);
                                        out.flush();
                                        try {
                                            pmm.logQueue.add(t+"::send::"+mk.toString()+"::"+mStr);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        try { sleep(10); } catch (InterruptedException e) {};//给10毫秒的延迟
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        long t=System.currentTimeMillis();
                        System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"发送消息线程出现异常:" + e.getMessage());
                    }
                }
            } catch (Exception e) {
                long t=System.currentTimeMillis();
                System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"发送消息线程出现异常:" + e.getMessage());
            } finally {
                try {
                    if (out!=null) try {out.close();out=null;} catch(Exception e) {out=null;throw e;};
                } catch(Exception e) {
                    e.printStackTrace();
                }
                this.isRunning=false;
            }
        }
    }

    /*
     * 接收消息+心跳线程
     */
    class ReceiveMsg extends Thread {
        private boolean isInterrupted=false;
        private boolean isRunning=true;
        private boolean canContinue=true;
        private int continueErrCodunt=0;
        private int sumErrCount=0;
        protected ReceiveMsg(String name) {
            super.setName(name);
        }
        protected void _interrupt(){
            isInterrupted = true;
            super.interrupt();
            this.interrupt();
        }
        public void run() {
            BufferedReader in=null;
            PrintWriter out=null;//若是确认消息才用得到
            this.isRunning=true;
            try {
                //若总线程运行(并且)Socket处理主线程可运行(并且)本线程可运行(并且)本线程逻辑正确[未中断(并且)可以继续]
                while(pmm.isServerRuning()&&SocketHandle.this.running&&(!isInterrupted&&canContinue)) {
                    try {
                        //接收消息数据
                        String revMsgStr="";
                        if (in==null) in=new BufferedReader(new InputStreamReader(SocketHandle.this.socket.getInputStream(), "UTF-8"));
                        revMsgStr=in.readLine();
                        long t=System.currentTimeMillis();
                        if (revMsgStr==null) continue;

                        SocketHandle.this.lastVisitTime=t;
                        //判断是否是心跳信号
                        if (revMsgStr.equals("b")) { //发送回执心跳
                            synchronized(socketSendLock) {
                                if (out==null) out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(SocketHandle.this.socket.getOutputStream(), "UTF-8")), true);
                                out.println("B");
                                out.flush();
                                System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"[发送回执心跳]");
                                try { sleep(10); } catch (InterruptedException e) {};//给10毫秒的延迟
                            }
                            continue;
                        }

                        try {
                            String temp123=SocketHandle.this.mk==null?"NULL":SocketHandle.this.mk.toString();
                            pmm.logQueue.add(t+"::recv::"+temp123+"::"+revMsgStr);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        //以下try要修改
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> recMap=(Map<String, Object>)JsonUtils.jsonToObj(revMsgStr, Map.class);
                            if (recMap!=null&&recMap.size()>0) {
                                //处理注册
                                Map<String, Object> retM = MobileUtils.dealMobileLinked(recMap, 1);
                                if ((""+retM.get("ReturnType")).equals("2003")) {
                                    String outStr="[{\"MsgId\":\""+SequenceUUID.getUUIDSubSegment(4)+"\",\"ReMsgId\":\""+recMap.get("MsgId")+"\",\"BizType\":\"NOLOG\"}]";//空，无内容包括已经收到
                                    synchronized(socketSendLock) {
                                        if (out==null) out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(SocketHandle.this.socket.getOutputStream(), "UTF-8")), true);
                                        out.println(outStr);
                                        out.flush();
                                        try { sleep(10); } catch (InterruptedException e) {};//给10毫秒的延迟
                                    }
                                } else {
                                    SocketHandle.this.mk=MobileUtils.getMobileKey(recMap);
                                    if (SocketHandle.this.mk!=null) {//存入接收队列
                                        if (!(recMap.get("BizType")+"").equals("REGIST")) pmm.getReceiveMemory().addPureQueue(recMap);
                                    }
                                }
                            }
                        } catch(Exception e) {
                            System.out.println("==============================================================");
                            System.out.println("EXCEPTIOIN::"+e.getClass().getName()+"/t"+e.getMessage());
                            System.out.println("JSONERROR::"+revMsgStr);
                            System.out.println("==============================================================");
                        }
                        continueErrCodunt=0;
                    } catch(Exception e) {
                        long t=System.currentTimeMillis();
                        System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"接收消息线程出现异常:"+e.getMessage());
                        if (e instanceof SocketException) {
                            canContinue=false;
                        } else {
                            if ( (++continueErrCodunt>=SocketHandle.this.smc.get_RecieveErr_ContinueCount())
                                ||(++sumErrCount>=SocketHandle.this.smc.get_RecieveErr_SumCount() )) {
                                 canContinue=false;
                             }
                        }
                    }//end try
                    try { sleep(10); } catch (InterruptedException e) {};
                }//end while
            } catch(Exception e) {
                long t=System.currentTimeMillis();
                System.out.println("<{"+t+"}"+DateUtils.convert2LocalStr("yyyy-MM-dd HH:mm:ss:SSS", new Date(t))+">"+socketDesc+"接收消息线程出现异常:" + e.getMessage());
            } finally {
                try {
                    if (in!=null) try {in.close();in=null;} catch(Exception e) {in=null;};
                    if (out!=null) try {out.close();out=null;} catch(Exception e) {out=null;};
                } catch(Exception e) {
                    e.printStackTrace();
                }
                this.isRunning=false;
            }
        }
    }
}