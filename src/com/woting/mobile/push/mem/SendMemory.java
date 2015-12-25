package com.woting.mobile.push.mem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.woting.mobile.model.MobileKey;
import com.woting.mobile.push.model.Message;
import com.woting.mobile.push.model.SendMessageList;

public class SendMemory {
    //java的占位单例模式===begin
    private static class InstanceHolder {
        public static SendMemory instance = new SendMemory();
    }
    public static SendMemory getInstance() {
        return InstanceHolder.instance;
    }
    //java的占位单例模式===end

    protected ConcurrentHashMap<MobileKey, ConcurrentLinkedQueue<Message>> msgMap;//将要发送的消息列表
    protected ConcurrentHashMap<MobileKey, SendMessageList> msgSendedMap;//已发送的信息情况

    /*
     * 初始化发送消息结构
     */
    private SendMemory() {
        msgMap=new ConcurrentHashMap<MobileKey, ConcurrentLinkedQueue<Message>>();
        msgSendedMap=new ConcurrentHashMap<MobileKey, SendMessageList>();
    }

    protected Map<MobileKey, ConcurrentLinkedQueue<Message>> getMsgMap() {
        return this.msgMap;
    }
    protected Map<MobileKey, SendMessageList> getMsgSendedMap() {
        return this.msgSendedMap;
    }

    //发送消息处理
    /**
     * 向某一设移动设备的输出队列中插入
     * @param mk 移动设备标识
     * @param msg 消息数据
     */
    public void addMsg2Queue(MobileKey mk, Message msg) {
        ConcurrentLinkedQueue<Message> mobileQueue=this.msgMap.get(mk);
        if (mobileQueue==null) {
            mobileQueue=new ConcurrentLinkedQueue<Message>();
            this.msgMap.put(mk, mobileQueue);
        }
        mobileQueue.add(msg);
    }
    /**
     * 向某一设移动设备的输出队列中插入唯一消息，唯一消息是指，同一时间某类消息对一个设备只能有一个消息内容。
     * @param mk 移动设备标识
     * @param msg 消息数据
     */
    public void addUniqueMsg2Queue(MobileKey mk, Message msg) {
        ConcurrentLinkedQueue<Message> mobileQueue=this.msgMap.get(mk);
        if (mobileQueue==null) {
            mobileQueue=new ConcurrentLinkedQueue<Message>();
            this.msgMap.put(mk, mobileQueue);
        }
        //唯一化处理
        //1-首先把一已发送列表中的同类消息删除
        SendMessageList sendedMl = this.msgSendedMap.get(mk);
        if (sendedMl!=null&&sendedMl.size()>0) {
            for (int i=sendedMl.size()-1; i>=0; i--) {
                Message m=sendedMl.get(i);
                if (msg.isSeamType(m)) sendedMl.remove(i);
            }
        }
        //2-加入现有的队列
        synchronized(mobileQueue) {
            List<Message> removeMsg = new ArrayList<Message>();
            for (Message m: mobileQueue) {
                if (msg.isSeamType(m)) removeMsg.add(m);
            }
            for (Message m: removeMsg) {
                mobileQueue.remove(m);
            }
            mobileQueue.add(msg);
        }
    }

    /**
     * 从某一设备的发送队列中取出要发送的消息，并从该队列中将这条消息移除
     * @param mk 设备标识
     * @return 消息
     */
    public Message pollTypeQueue(MobileKey mk) {
        if (this.msgMap==null) return null;
        if (this.msgMap.get(mk)==null) return null;
        return this.msgMap.get(mk).poll();
    }

    /**
     * 从某一设备的发送队列中取出要发送的消息，但不从该队列移除这条消息，这条消息还存在于设备发送队列中
     * @param mk 设备标识
     * @return 消息
     */
    public Message peekMobileQueue(MobileKey mk) {
        if (this.msgMap==null) return null;
        if (this.msgMap.get(mk)==null) return null;
        return this.msgMap.get(mk).peek();
    }

    //已发送消息处理
    /**
     * 向某一设移动设备的已发送列表插入数据
     * @param mk
     * @param msg
     * @return 插入成功返回true，否则返回false
     * @throws IllegalAccessException 
     */
    public boolean addSendedMsg(MobileKey mk, Message msg) throws IllegalAccessException {
        SendMessageList mobileSendedList=this.msgSendedMap.get(mk);
        if (mobileSendedList==null) {
            mobileSendedList=new SendMessageList(mk);
            this.msgSendedMap.put(mk, mobileSendedList);
        }
        return mobileSendedList.add(msg);
    }

    /**
     * 根据某一移动设备标识，获得已发送消息列表
     * @param mk
     * @return
     */
    public SendMessageList getSendedMessagList(MobileKey mk) {
        return this.msgSendedMap.get(mk);
    }
}