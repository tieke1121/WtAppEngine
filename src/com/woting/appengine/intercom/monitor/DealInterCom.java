package com.woting.appengine.intercom.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.spiritdata.framework.util.SequenceUUID;
import com.woting.appengine.common.util.MobileUtils;
import com.woting.appengine.intercom.mem.GroupMemoryManage;
import com.woting.appengine.intercom.model.GroupInterCom;
import com.woting.appengine.mobile.model.MobileKey;
import com.woting.appengine.mobile.push.mem.PushMemoryManage;
import com.woting.appengine.mobile.push.model.CompareMsg;
import com.woting.appengine.mobile.push.model.Message;
import com.woting.passport.UGA.persistence.pojo.UserPo;

public class DealInterCom extends Thread {
    private PushMemoryManage pmm=PushMemoryManage.getInstance();
    private GroupMemoryManage gmm=GroupMemoryManage.getInstance();

    /**
     * 给线程起一个名字的构造函数
     * @param name 线程名称
     */
    public DealInterCom(String name) {
        super("对讲过程监听处理线程"+((name==null||name.trim().length()==0)?"":"::"+name));
    }

    @Override
    public void run() {
        System.out.println(this.getName()+"开始执行");
        String tempStr="";
        while(true) {
            try {
                sleep(10);
                //读取Receive内存中的typeMsgMap中的内容
                Message m=pmm.getReceiveMemory().pollTypeQueue("INTERCOM_CTL");
                if (m==null) continue;
                
                if (m.getCmdType().equals("GROUP")) {
                    if (m.getCommand().equals("1")) {
                        tempStr="处理消息[MsgId="+m.getMsgId()+"]-用户进入组::(User="+m.getFromAddr()+";Group="+((Map)m.getMsgContent()).get("GroupId")+")";
                        System.out.println(tempStr);
                        (new EntryGroup("{"+tempStr+"}处理线程", m)).start();
                    } else if (m.getCommand().equals("2")) {
                        tempStr="处理消息[MsgId="+m.getMsgId()+"]-用户退出组::(User="+m.getFromAddr()+";Group="+((Map)m.getMsgContent()).get("GroupId")+")";
                        System.out.println(tempStr);
                        (new ExitGroup("{"+tempStr+"}处理线程", m)).start();
                    }
                } else if (m.getCmdType().equals("PTT")) {
                    if (m.getCommand().equals("1")) {
                        tempStr="处理消息[MsgId="+m.getMsgId()+"]-开始对讲::(User="+m.getFromAddr()+";Group="+((Map)m.getMsgContent()).get("GroupId")+")";
                        System.out.println(tempStr);
                        (new BeginPTT("{"+tempStr+"}处理线程", m)).start();
                    } else if (m.getCommand().equals("2")) {
                        tempStr="处理消息[MsgId="+m.getMsgId()+"]-结束对讲::(User="+m.getFromAddr()+";Group="+((Map)m.getMsgContent()).get("GroupId")+")";
                        System.out.println(tempStr);
                        (new EndPTT("{"+tempStr+"}处理线程", m)).start();
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    //进入组处理
    class EntryGroup extends Thread {
        private Message sourceMsg;//源消息
        protected EntryGroup(String name, Message sourceMsg) {
            super.setName(name);
            this.sourceMsg=sourceMsg;
        }
        public void run() {
            String groupId="";
            try {
                groupId+=((Map)sourceMsg.getMsgContent()).get("GroupId");
            } catch(Exception e) {}
            if (groupId.length()==0) return;
            Message retMsg=new Message();
            retMsg.setMsgId(SequenceUUID.getUUIDSubSegment(4));
            retMsg.setReMsgId(sourceMsg.getMsgId());

            retMsg.setToAddr(sourceMsg.getFromAddr());
            retMsg.setFromAddr(sourceMsg.getToAddr());

            retMsg.setMsgType(-1);
            retMsg.setAffirm(0);

            retMsg.setMsgBizType(sourceMsg.getMsgBizType());
            retMsg.setCmdType(sourceMsg.getCmdType());
            retMsg.setCommand("-1");

            retMsg.setSendTime(System.currentTimeMillis());

            Map<String, Object> dataMap=new HashMap<String, Object>();
            dataMap.put("GroupId", groupId);
            retMsg.setMsgContent(dataMap);
            GroupInterCom gic=gmm.getGroupInterCom(groupId);
            MobileKey mk=MobileUtils.getMobileKey(sourceMsg);
            if (mk!=null&&gic!=null) {
                if (mk.isUser()) {
                    Map<String, Object> retM=gic.insertEntryUser(mk);
                    String rt = (String)retM.get("returnType");
                    if (rt.equals("3")) {//该用户不在指定组
                        retMsg.setReturnType("1002");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else if (rt.equals("2")) {//该用户已经在制定组
                        retMsg.setReturnType("1003");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else {//加入成功
                        retMsg.setReturnType("1001");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);

                        if (retM.containsKey("needBroadCast")) {
                            //广播消息信息组织
                            Message bMsg=getBroadCastMessage(retMsg);
                            bMsg.setCommand("b1");
                            dataMap=new HashMap<String, Object>();
                            dataMap.put("GroupId", groupId);
                            List<Map<String, Object>> inGroupUsers=new ArrayList<Map<String,Object>>();
                            Map<String, Object> um;
                            UserPo up;
                            Map<String, UserPo> entryGroupUsers=(Map<String, UserPo>)retM.get("entryGroupUsers");
                            for (String k: entryGroupUsers.keySet()) {
                                up=entryGroupUsers.get(k);
                                um=new HashMap<String, Object>();
                                //TODO 这里的号码可能还需要处理
                                um.put("UserId", up.getUserId());
                                um.put("InnerPhoneNum", up.getInnerPhoneNum());
                                inGroupUsers.add(um);
                            }
                            dataMap.put("InGroupUsers", inGroupUsers);
                            bMsg.setMsgContent(dataMap);
                            //发送广播消息
                            for (String k: entryGroupUsers.keySet()) {
                                String _sp[] = k.split("::");
                                mk=new MobileKey();
                                mk.setMobileId(_sp[0]);
                                mk.setUserId(_sp[1]);
                                pmm.getSendMemory().addUniqueMsg2Queue(mk, bMsg, new CompareGroupMsg());
                            }
                        }
                    }
                } else {
                    retMsg.setReturnType("1000");
                    pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                }
            } else {
                retMsg.setReturnType("1000");
                pmm.getSendMemory().addMsg2Queue(mk, retMsg);
            }
        }
    }
    //退出组处理
    class ExitGroup extends Thread {
        private Message sourceMsg;//源消息
        protected ExitGroup(String name, Message sourceMsg) {
            super.setName(name);
            this.sourceMsg=sourceMsg;
        }
        public void run() {
            String groupId="";
            try {
                groupId+=((Map)sourceMsg.getMsgContent()).get("GroupId");
            } catch(Exception e) {}
            if (groupId.length()==0) return;
            Message retMsg=new Message();
            retMsg.setMsgId(SequenceUUID.getUUIDSubSegment(4));
            retMsg.setReMsgId(sourceMsg.getMsgId());

            retMsg.setToAddr(sourceMsg.getFromAddr());
            retMsg.setFromAddr(sourceMsg.getToAddr());

            retMsg.setMsgType(1);
            retMsg.setAffirm(0);

            retMsg.setMsgBizType(sourceMsg.getMsgBizType());
            retMsg.setCmdType(sourceMsg.getCmdType());
            retMsg.setCommand("-2");

            retMsg.setSendTime(System.currentTimeMillis());

            Map<String, Object> dataMap=new HashMap<String, Object>();
            dataMap.put("GroupId", groupId);
            retMsg.setMsgContent(dataMap);
            GroupInterCom gic=gmm.getGroupInterCom(groupId);
            MobileKey mk=MobileUtils.getMobileKey(sourceMsg);
            if (mk!=null&&gic!=null) {
                if (mk.isUser()) {
                    Map<String, Object> retM=gic.delEntryUser(mk);
                    String rt = (String)retM.get("returnType");
                    if (rt.equals("3")) {//该用户不在指定组
                        retMsg.setReturnType("1002");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else if (rt.equals("2")) {//该用户已经在制定组
                        retMsg.setReturnType("1003");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else {//正式加入，这时可以广播了
                        retMsg.setReturnType("1001");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);

                        if (retM.containsKey("needBroadCast")) {
                            //广播消息信息组织
                            Message bMsg=getBroadCastMessage(retMsg);
                            bMsg.setCommand("b1");
                            dataMap=new HashMap<String, Object>();
                            dataMap.put("GroupId", groupId);
                            List<Map<String, Object>> inGroupUsers=new ArrayList<Map<String,Object>>();
                            Map<String, Object> um;
                            UserPo up;
                            Map<String, UserPo> entryGroupUsers=(Map<String, UserPo>)retM.get("entryGroupUsers");
                            for (String k: entryGroupUsers.keySet()) {
                                up=entryGroupUsers.get(k);
                                um=new HashMap<String, Object>();
                                //TODO 这里的号码可能还需要处理
                                um.put("UserId", up.getUserId());
                                um.put("InnerPhoneNum", up.getInnerPhoneNum());
                                inGroupUsers.add(um);
                            }
                            dataMap.put("InGroupUsers", inGroupUsers);
                            bMsg.setMsgContent(dataMap);
                            //发送广播消息
                            for (String k: entryGroupUsers.keySet()) {
                                String _sp[] = k.split("::");
                                mk=new MobileKey();
                                mk.setMobileId(_sp[0]);
                                mk.setUserId(_sp[1]);
                                pmm.getSendMemory().addUniqueMsg2Queue(mk, bMsg, new CompareGroupMsg());
                            }
                        }
                    }
                } else {
                    retMsg.setReturnType("1000");
                    pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                }
            } else {
                retMsg.setReturnType("1000");
                pmm.getSendMemory().addMsg2Queue(mk, retMsg);
            }
        }
    }

    /*
     * 开始对讲处理
     */
    class BeginPTT extends Thread {
        private Message sourceMsg;//源消息
        protected BeginPTT(String name, Message sourceMsg) {
            super.setName(name);
            this.sourceMsg=sourceMsg;
        }
        public void run() {
            String groupId="";
            try {
                groupId+=((Map)sourceMsg.getMsgContent()).get("GroupId");
            } catch(Exception e) {}
            if (groupId.length()==0) return;
            Message retMsg=new Message();
            retMsg.setMsgId(SequenceUUID.getUUIDSubSegment(4));
            retMsg.setReMsgId(sourceMsg.getMsgId());

            retMsg.setToAddr(sourceMsg.getFromAddr());
            retMsg.setFromAddr(sourceMsg.getToAddr());

            retMsg.setMsgType(-1);
            retMsg.setAffirm(0);

            retMsg.setMsgBizType(sourceMsg.getMsgBizType());
            retMsg.setCmdType(sourceMsg.getCmdType());
            retMsg.setCommand("-1");

            retMsg.setSendTime(System.currentTimeMillis());

            GroupInterCom gic=gmm.getGroupInterCom(groupId);
            MobileKey mk=MobileUtils.getMobileKey(sourceMsg);
            if (mk!=null) {
                if (mk.isUser()) {
                    Map<String, Object> dataMap=new HashMap<String, Object>();
                    dataMap.put("GroupId", groupId);
                    retMsg.setMsgContent(dataMap);

                    Map<String, UserPo> _m=gic.setSpeaker(mk);
                    if (_m.containsKey("E")) {
                        retMsg.setReturnType("1003");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else if (_m.containsKey("F")) {
                        retMsg.setReturnType("1002");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else {//成功可以开始对讲了
                        retMsg.setReturnType("1001");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);

                        //广播开始对讲消息
                        Message bMsg=getBroadCastMessage(retMsg);
                        bMsg.setCommand("b1");
                        dataMap=new HashMap<String, Object>();
                        dataMap.put("GroupId", groupId);
                        dataMap.put("GroupPhoneNum", "3000"); //TODO 这个需要修改
                        dataMap.put("TalkUserId", mk.getUserId());
                        bMsg.setMsgContent(dataMap);
                        //发送广播消息
                        Map<String, UserPo> entryGroupUsers=gic.getEntryGroupUserMap();
                        for (String k: entryGroupUsers.keySet()) {
                            String _sp[] = k.split("::");
                            mk=new MobileKey();
                            mk.setMobileId(_sp[0]);
                            mk.setUserId(_sp[1]);
                            pmm.getSendMemory().addUniqueMsg2Queue(mk, bMsg, new CompareGroupMsg());
                        }
                    }
                } else {
                    retMsg.setReturnType("1000");
                    try {
                        pmm.getSendMemory().addSendedMsg(mk, retMsg);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
    /*
     * 结束对讲处理
     */
    class EndPTT extends Thread {
        private Message sourceMsg;//源消息
        protected EndPTT(String name, Message sourceMsg) {
            super.setName(name);
            this.sourceMsg=sourceMsg;
        }
        public void run() {
            String groupId="";
            try {
                groupId+=((Map)sourceMsg.getMsgContent()).get("GroupId");
            } catch(Exception e) {}
            if (groupId.length()==0) return;
            Message retMsg=new Message();
            retMsg.setMsgId(SequenceUUID.getUUIDSubSegment(4));
            retMsg.setReMsgId(sourceMsg.getMsgId());

            retMsg.setToAddr(sourceMsg.getFromAddr());
            retMsg.setFromAddr(sourceMsg.getToAddr());

            retMsg.setMsgType(-1);
            retMsg.setAffirm(0);

            retMsg.setMsgBizType(sourceMsg.getMsgBizType());
            retMsg.setCmdType(sourceMsg.getCmdType());
            retMsg.setCommand("-2");

            retMsg.setSendTime(System.currentTimeMillis());

            GroupInterCom gic=gmm.getGroupInterCom(groupId);
            MobileKey mk=MobileUtils.getMobileKey(sourceMsg);
            if (mk!=null) {
                if (mk.isUser()) {
                    Map<String, Object> dataMap=new HashMap<String, Object>();
                    dataMap.put("GroupId", groupId);
                    retMsg.setMsgContent(dataMap);

                    int _r=gic.removeSpeaker(mk);
                    if (_r==-1) {
                        retMsg.setReturnType("1002");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else if (_r==0) {
                        retMsg.setReturnType("1003");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);
                    } else {//结束对讲
                        retMsg.setReturnType("1001");
                        pmm.getSendMemory().addMsg2Queue(mk, retMsg);

                        //广播开始对讲消息
                        Message bMsg=getBroadCastMessage(retMsg);
                        bMsg.setCommand("b2");
                        dataMap=new HashMap<String, Object>();
                        dataMap.put("GroupId", groupId);
                        dataMap.put("TalkUserId", mk.getUserId());
                        bMsg.setMsgContent(dataMap);
                        //发送广播消息
                        Map<String, UserPo> entryGroupUsers=gic.getEntryGroupUserMap();
                        for (String k: entryGroupUsers.keySet()) {
                            String _sp[] = k.split("::");
                            mk=new MobileKey();
                            mk.setMobileId(_sp[0]);
                            mk.setUserId(_sp[1]);
                            pmm.getSendMemory().addUniqueMsg2Queue(mk, bMsg, new CompareGroupMsg());
                        }
                    }
                } else {
                    retMsg.setReturnType("1000");
                    try {
                        pmm.getSendMemory().addSendedMsg(mk, retMsg);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private Message getBroadCastMessage(Message msg) {
        Message ret=new Message();
        ret.setMsgId(SequenceUUID.getUUIDSubSegment(4));

        ret.setFromAddr(msg.getFromAddr());
        ret.setToAddr(msg.getToAddr());

        ret.setMsgType(1);
        ret.setAffirm(msg.getAffirm());

        ret.setMsgBizType(msg.getMsgBizType());
        ret.setCmdType(msg.getCmdType());

        ret.setSendTime(System.currentTimeMillis());
        return ret;
    }

    class CompareGroupMsg implements CompareMsg {
        @Override
        public boolean compare(Message msg1, Message msg2) {
            if (msg1.getFromAddr().equals(msg2.getFromAddr())
              &&msg1.getToAddr().equals(msg2.getToAddr())
              &&msg1.getMsgBizType().equals(msg2.getMsgBizType())
              &&msg1.getCmdType().equals(msg2.getCmdType())
              &&msg1.getCommand().equals(msg2.getCommand()) ) {
                if (msg1.getMsgContent()==null&&msg2.getMsgContent()==null) return true;
                if (((msg1.getMsgContent()!=null&&msg2.getMsgContent()!=null))
                  &&(((Map)msg1.getMsgContent()).get("GroupId").equals(((Map)msg2.getMsgContent()).get("GroupId")))) return true;
            }
            return false;
        }
        
    }
}