package mcgill.game;

import java.util.EventObject;
import java.util.Map;

import mcgill.poker.Hand;

public class ClientEvent extends EventObject {

	private static final long serialVersionUID = -6303128939724816459L;	
	
	public static int HAND = 0;
	public static int ACTION_GET = 1;
	public static int ACTION_REC = 2;
	public static int USER = 3;
	public static int MESSAGE = 4;
	
	private int type;
	private int action;
	private int callAmount;
	private Map<String, Hand> hands;
	private User[] users;
	private String chatId;
	
	public ClientEvent(Object source) {
		super(source);
	}
	
	public int getType() {
		return this.type;
	}
	
	public void setType(int type) {
		this.type = type;
	}

	public int getAction() {
		return action;
	}

	public void setAction(int action) {
		this.action = action;
	}

	public int getCallAmount() {
		return callAmount;
	}

	public void setCallAmount(int callAmount) {
		this.callAmount = callAmount;
	}
	
	public Map<String, Hand> getHands() {
		return this.hands;
	}
	
	public void setHands(Map<String, Hand> hands) {
		this.hands = hands;
	}

	public User[] getUsers() {
		return users;
	}

	public void setUsers(User[] users) {
		this.users = users;
	}

	public String getChatId() {
		return this.chatId;
	}

	public void setChatId(String chatId) {
		this.chatId = chatId;
	}

}
