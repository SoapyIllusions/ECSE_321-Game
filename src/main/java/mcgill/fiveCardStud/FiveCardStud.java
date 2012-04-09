package mcgill.fiveCardStud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mcgill.game.ClientNotification;
import mcgill.game.Config;
import mcgill.game.Database;
import mcgill.game.Server;
import mcgill.game.User;
import mcgill.poker.Deck;
import mcgill.poker.Hand;
import mcgill.poker.HandRank;
import mcgill.poker.Player;
import mcgill.poker.Pot;
import mcgill.poker.OutOfMoneyException;
import mcgill.poker.TooFewCardsException;
import mcgill.poker.TooManyCardsException;

public class FiveCardStud implements Runnable {
	public static final int FOLDED = -1;
	public static final int BETTING = 0;
	public static final int ALL_IN = 1;
	
	private int maxRaises;
	private int street;
	private int raises;
	private int lowBet;
	private int bringIn;
	private Deck deck;
	private List<Player> players;
	private List<Pot> pots;
	private int startingPlayer;
	
	public FiveCardStud(List<Player> players, int lowBet, int maxRaises, int bringIn) {
		this.raises = 0;
		this.players = players;
		this.pots= new ArrayList<Pot>();
		this.deck = new Deck();
		this.street = 2;
		this.lowBet = lowBet;
		this.maxRaises = maxRaises;
		this.bringIn = bringIn;
		this.startingPlayer = 0;
	}
	
	public void run() {
		try {
			this.playRound();
			System.out.println("Round is done");
		} catch (Exception e) {
			System.out.println("*** PLAY ROUND EXCEPTION ***");
			e.printStackTrace();
		}
	}
	
	public int getTotalPot() {
		int total = 0;
		
		for (Player player : this.players) {
			total += player.getAmountInPots();
		}
		
		return total;
	}
	
	public void playRound() throws TooFewCardsException, TooManyCardsException, OutOfMoneyException {
		while (this.street < 6) {
			if (this.street == 2) {
				initialize();
			}
			
			if (onlyOneBetting()) break;
			
			for(Player player : this.players) {
				player.addCard(this.deck.getTop());
				emitHands();
			}
			
			betting();
		}
		
		makePots();
		dividePots();
		
		Database db = new Database(Config.REDIS_HOST, Config.REDIS_PORT);
		String winner = null;
		Map<String, Integer> credit_map = new HashMap<String, Integer>();
		
		for (Player player : this.players) {
			User user = db.getUser(player.getUsername(), false);
			user.setCredits(player.getTotalMoney());
			db.setUser(user);
			
			credit_map.put(player.getUsername(), player.getTotalMoney());
			
			if (player.isWinner()) {
				winner = player.getUsername();
			}
		}
		
		emitEndOfRound(winner, credit_map);
		db.close();
	}
	
	private void emitEndOfRound(String winner, Map<String, Integer> credit_map) {
		EndOfRound end = new EndOfRound(winner, credit_map);
				
		for (Player player : this.players) {
			String session_str = Server.getUserSession(player.getUsername());
			ClientNotification notification = new ClientNotification(session_str);
			notification.sendEndOfRound(end);
			notification.close();
		}
	}

	private void potAndStatusNotification(Player player) {
		int[] current = new int[2];
		
		current[0] = getTotalPot();
		current[1] = player.getStatus();
		
		String session_str = Server.getUserSession(player.getUsername());
		ClientNotification notification = new ClientNotification(session_str);
		
		notification.potAndStatus(current);
		notification.close();
	}
	
	private int getAction(String username, int[] limits) {
		String session_str = Server.getUserSession(username);
		ClientNotification notification = new ClientNotification(session_str);
		
		String command = notification.getCommand(limits);
		notification.close();
		
		return Integer.parseInt(command);
	}

	private void emitHands() {
		Map<String, Hand> hands = new HashMap<String, Hand>();
		
		for (Player player : this.players) {
			hands.put(player.getUsername(), player.getHand());
		}
		
		for (Player player : this.players) {
			String session_str = Server.getUserSession(player.getUsername());
			ClientNotification notification = new ClientNotification(session_str);
			notification.sendHand(hands);
			notification.close();
		}
	}
	
	private void initialize() throws TooFewCardsException, TooManyCardsException, OutOfMoneyException {
		for(Player player : this.players) {
			try {
				player.bet(this.lowBet/4);
				
				player.addCard(this.deck.getTop()); //face down
				emitHands();
			} catch (OutOfMoneyException e) {
				this.players.remove(player);
			} catch (TooManyCardsException e) {
				throw new RuntimeException(e.getMessage());
			}
		}
	}
	
	private void betting() throws OutOfMoneyException, TooFewCardsException, TooManyCardsException {
		int i = 1;
		boolean continueStreet = true;
		
		findStartingPlayer();
		
		while (continueStreet) {
			for (int j = startingPlayer; j < (startingPlayer + this.players.size()); j++){
				
				if ((noMoreCalls() || onlyOneBetting()) && i != 1) {
					continueStreet = false;
					this.startingPlayer = 0;
					this.raises = 0;
					this.street++;
					break;
				}
				
				int index = j;
				int betLimit;
			
				//Betting limits based on which street the round is in
				if (this.street == 2) {
					if (index == this.startingPlayer && i == 1) {
						betLimit = this.bringIn; 
					} else if (index == (this.startingPlayer + 1) && i == 1) {
						betLimit = this.lowBet - this.bringIn;
					} else {
						betLimit = this.lowBet;
					}
				} else if (this.street == 3) {
					betLimit = this.lowBet;
				} else {
					betLimit = 2*(this.lowBet);
				}
				
				//Wraps around 
				if (index >= this.players.size()) {
					index = j - this.players.size();
				}
				
				Player currentPlayer = players.get(index);
				
				if (currentPlayer.isBetting()) {
					potAndStatusNotification(players.get(index));
					
					int callAmount = getCallAmount() - currentPlayer.getAmountInPots();
					
					if (this.street == 2 && this.startingPlayer == index && i == 1) {
						callAmount = this.bringIn;
					} else if (raises >= maxRaises) {
						betLimit = callAmount;
					}
					
					int limitAmount = callAmount + betLimit;
					
					int[] limits = {callAmount, limitAmount};	
					
					int action = getAction(players.get(index).getUsername(), limits);
					
					System.out.println("Action for " + players.get(index).getUsername() + " is: " + action);
					
					if (action == 0);
					else if (action == -1) {
						currentPlayer.setStatus(FOLDED);
					} else {
						if (action > callAmount) {this.raises++;}
						if (action >= currentPlayer.getTotalMoney()) {
							currentPlayer.bet(currentPlayer.getTotalMoney());
							currentPlayer.setStatus(ALL_IN);
						} else {
							currentPlayer.bet(action);
						}
					}
					
					//GameTest.printAmountInPots(players.get(index));
					System.out.println("\n ---------------------------------------- \n");
				}	
			}
			
			i++;
		}
	}

	//Fix 2,3,4 cards (it for some reason does not just look for pairs, three of a kind, four of a kind, 2 pairs)
	private void findStartingPlayer() throws TooFewCardsException, TooManyCardsException {
		int i = 0; 
		
		if (street == 2) {
			for (Player player : this.players) {
				if (HandRank.compareHands(players.get(startingPlayer).getHand(), player.getHand(), 1) == 0) {
					startingPlayer = i;
				}
				i++;
			}
		} else {
			for (Player player : this.players) {
				if (player.isBetting() && (HandRank.compareHands(players.get(startingPlayer).getHand(), player.getHand(), street - 1) == 1)) {
					startingPlayer = i;
				}
				i++;
			}
		}
	}
	
	private void makePots() {
		Player[] playerArray = new Player[this.players.size()]; 
		this.players.toArray(playerArray);
		
		Arrays.sort(playerArray);
		
		int[] amountInPots = new int[playerArray.length];
		for (int i = 0; i < playerArray.length; i++) {
			amountInPots[i] = playerArray[i].getAmountInPots();
		}
		
		for (int i = 0; i < playerArray.length; i++) {
			Player player = playerArray[i];
			
			if (player.isAllIn() && (amountInPots[i] != 0)) {
				Pot pot = new Pot();
				pot.setLimit(amountInPots[i]);
				
				for (int j = 0; j < playerArray.length; j++) {
					if (amountInPots[j] != 0) {
						if (playerArray[j].isFolded()) {
							if (amountInPots[j] > pot.getLimit()) {
								pot.addToPot(pot.getLimit());
								amountInPots[j] -= pot.getLimit();
							} else {
								pot.addToPot(amountInPots[j]);
								amountInPots[j] = 0;
							}
						} else {
							if (amountInPots[j] > pot.getLimit()) {
								if (pot.containsPlayer(playerArray[j])) {
									pot.addToPot(pot.getLimit());
								} else {
									pot.addPlayer(playerArray[j], pot.getLimit());
								}
								amountInPots[j] -= pot.getLimit();
							} else {
								if (pot.containsPlayer(playerArray[j])) {
									pot.addToPot(amountInPots[j]);
								} else {
									pot.addPlayer(playerArray[j], amountInPots[j]);
								}
								amountInPots[j] = 0;
							}
						}
					}
				}
				
				this.pots.add(pot);
			}
		}
		
		Pot pot = new Pot();
		
		for (int j = 0; j < playerArray.length; j++) {
			if (amountInPots[j] != 0) {
				if (playerArray[j].isFolded()) {
					pot.addToPot(amountInPots[j]);
					amountInPots[j] = 0;
				} else {
					if (pot.containsPlayer(playerArray[j])) {
						pot.addToPot(amountInPots[j]);
					} else {
						pot.addPlayer(playerArray[j], amountInPots[j]);
					}
					amountInPots[j] = 0;
				}
			}
		}
		
		this.pots.add(pot);
	}
	
	private boolean onlyOneBetting() {
		int playersBetting = 0;
		
		for (Player player : this.players) {
			if (player.isBetting()) {
				playersBetting++;
			}
		}
		
		if (playersBetting <= 1) {
			return true;
		} else {
			return false;
		}
	}
	
	private boolean noMoreCalls() {
		for (Player player : this.players) {
			if (player.isBetting()) {
				int moneyToMatch = player.getAmountInPots();
				for (Player tmpPlayer : this.players) {
					if (tmpPlayer.isBetting()) {
						if (tmpPlayer.getAmountInPots() != moneyToMatch) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}
	
	private int getCallAmount() {
		int callAmount = 0;
		
		for (Player player : this.players) {
			if ((!player.isFolded()) && (player.getAmountInPots() > callAmount)) {
				callAmount = player.getAmountInPots();
			}
		}
		
		return callAmount;
	}
	
	private void dividePots() throws TooFewCardsException, TooManyCardsException {
		//need to account for ties
		
		for (Pot pot : this.pots) {
			int winningPlayer = 0;
			int winners = 0;
			int i = 0;
			ArrayList<Player> potPlayers = pot.getPlayers();
			
			for (Player player : potPlayers) {
				if ((!player.isFolded()) && (HandRank.compareHands(potPlayers.get(winningPlayer).getHand(), player.getHand(), 5) == 1)) {
					winningPlayer = i;
				}
				i++;
			}
			
			Player winner = potPlayers.get(winningPlayer);
			
			for (Player player : this.players) {
				if ((!player.isFolded()) && (HandRank.compareHands(winner.getHand(), player.getHand(), 5) == -1)) {
					player.setWinner(true);
					winners++;
				}
			}
			
			for (Player player : this.players) {
				if (player.isWinner()) {
					player.addMoney(pot.getTotalAmount()/winners);
					player.setWinner(false);
				}
			}
		}
	}
}

