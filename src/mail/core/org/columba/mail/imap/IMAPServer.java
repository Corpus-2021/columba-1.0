// The contents of this file are subject to the Mozilla Public License Version
// 1.1
//(the "License"); you may not use this file except in compliance with the
//License. You may obtain a copy of the License at http://www.mozilla.org/MPL/
//
//Software distributed under the License is distributed on an "AS IS" basis,
//WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
//for the specific language governing rights and
//limitations under the License.
//
//The Original Code is "The Columba Project"
//
//The Initial Developers of the Original Code are Frederik Dietz and Timo
// Stich.
//Portions created by Frederik Dietz and Timo Stich are Copyright (C) 2003.
//
//All Rights Reserved.
package org.columba.mail.imap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;
import javax.swing.JOptionPane;

import org.columba.api.command.IStatusObservable;
import org.columba.core.base.Blowfish;
import org.columba.core.base.ListTools;
import org.columba.core.command.CommandCancelledException;
import org.columba.core.filter.FilterCriteria;
import org.columba.core.filter.FilterRule;
import org.columba.core.gui.base.MultiLineLabel;
import org.columba.core.gui.frame.FrameManager;
import org.columba.mail.config.AccountItem;
import org.columba.mail.config.ImapItem;
import org.columba.mail.config.IncomingItem;
import org.columba.mail.filter.MailFilterCriteria;
import org.columba.mail.folder.IMailbox;
import org.columba.mail.folder.command.MarkMessageCommand;
import org.columba.mail.folder.headercache.CachedHeaderfields;
import org.columba.mail.folder.imap.IMAPFolder;
import org.columba.mail.gui.util.PasswordDialog;
import org.columba.mail.message.ColumbaHeader;
import org.columba.mail.message.IHeaderList;
import org.columba.mail.util.AuthenticationManager;
import org.columba.mail.util.AuthenticationSecurityComparator;
import org.columba.mail.util.MailResourceLoader;
import org.columba.ristretto.auth.AuthenticationException;
import org.columba.ristretto.auth.AuthenticationFactory;
import org.columba.ristretto.imap.IMAPDate;
import org.columba.ristretto.imap.IMAPDisconnectedException;
import org.columba.ristretto.imap.IMAPException;
import org.columba.ristretto.imap.IMAPFlags;
import org.columba.ristretto.imap.IMAPHeader;
import org.columba.ristretto.imap.IMAPListener;
import org.columba.ristretto.imap.IMAPProtocol;
import org.columba.ristretto.imap.IMAPResponse;
import org.columba.ristretto.imap.ListInfo;
import org.columba.ristretto.imap.MailboxStatus;
import org.columba.ristretto.imap.NamespaceCollection;
import org.columba.ristretto.imap.SearchKey;
import org.columba.ristretto.imap.SequenceSet;
import org.columba.ristretto.io.ConnectionDroppedException;
import org.columba.ristretto.io.SequenceInputStream;
import org.columba.ristretto.message.Header;
import org.columba.ristretto.message.MailboxInfo;
import org.columba.ristretto.message.MimeTree;

/**
 * IMAPStore encapsulates IMAPProtocol and the parsers for IMAPFolder.
 * <p>
 * This way {@link IMAPFolder}doesn't need to do any parsing work, etc.
 * <p>
 * Every {@link IMAPFolder}of a single account has also an
 * {@link IMAPRootFolder}, which keeps a reference to {@link IMAPServer}.
 * Which itself uses {@link IMAPProtocol}.
 * <p>
 * IMAPStore handles the current state of connection:
 * <ul>
 * <li>STATE_NONAUTHENTICATE - not authenticated</li>
 * <li>STATE_AUTHENTICATE - authenticated</li>
 * <li>STATE_SELECTED - mailbox is selected</li>
 * </ul>
 * <p>
 * It keeps a reference to the currently selected mailbox.
 * <p>
 * IMAPFolder shouldn't use IMAPProtocol directly, instead it should use
 * IMAPStore.
 * 
 * @author fdietz
 */
public class IMAPServer implements IMAPListener, Observer {

	private static final int STEP_SIZE = 50;

	private static final int UID_FETCH_STEPS = 500;

	private static final Logger LOG = Logger.getLogger("org.columba.mail.imap");

	private static final Charset UTF8 = Charset.forName("UTF-8");

	private static final Charset DEFAULT = Charset.forName(System
			.getProperty("file.encoding"));

	/**
	 * currently selected mailbox
	 */
	private IMailbox selectedFolder;

	/**
	 * Holds the actual MailboxStatus. Updated by the IMAPListener.
	 */
	private MailboxStatus selectedStatus;

	/**
	 * mailbox name delimiter
	 * <p>
	 * example: "/" (uw-imap), or "." (cyrus)
	 */
	private String delimiter;

	/**
	 * reference to IMAP protocol
	 */
	private IMAPProtocol protocol;

	/**
	 * configuration options of this IMAP account
	 */
	private ImapItem item;

	private MimeTree aktMimeTree;

	private Object aktMessageUid;

	private MailboxInfo messageFolderInfo;

	private boolean firstLogin;

	boolean usingSSL;

	String[] capabilities;

	private long lastCommunication;

	private IStatusObservable observable;
	
	// minimal unchecked time is 30 Seconds
	private int MIN_IDLE = 30 * 1000; // in ms

	// Used to control the state in which
	// the automatic updated mechanism is
	private boolean updatesEnabled;

	private IFirstLoginAction firstLoginAction;

	private IUpdateFlagAction updateFlagAction;

	private IExistsChangedAction existsChangedAction;

	public IMAPServer(ImapItem item) {
		this.item = item;

		item.getRoot().addObserver(this);
		
		// create IMAP protocol
		protocol = new IMAPProtocol(item.get("host"), item.getInteger("port"));
		// register interest on status updates
		protocol.addIMAPListener(this);

		firstLogin = true;
		usingSSL = false;

		lastCommunication = System.currentTimeMillis();
	}

	/**
	 * @return
	 */
	protected IStatusObservable getObservable() {
		return observable;
	}

	/**
	 * @param message
	 */
	protected void printStatusMessage(String message) {
		if (getObservable() != null) {
			getObservable().setMessage(item.get("host") + ": " + message);
		}
	}

	/**
	 * Get mailbox path delimiter
	 * <p>
	 * example: "/" (uw-imap), or "." (cyrus)
	 * 
	 * @return delimiter
	 */
	public String getDelimiter() throws IOException, IMAPException,
			CommandCancelledException {
		if (delimiter == null) {
			// try to determine delimiter
			delimiter = fetchDelimiter();
		}

		return delimiter;
	}

	/**
	 * Logout cleanly.
	 * 
	 * @throws Exception
	 */
	public void logout() throws Exception {
		if (protocol.getState() != IMAPProtocol.NOT_CONNECTED) {
			try {
				protocol.logout();
			} catch (Exception e) {
				// don't care
			}
		}
	}

	private void openConnection() throws IOException, IMAPException,
			CommandCancelledException {
		printStatusMessage(MailResourceLoader.getString("statusbar", "message",
				"connecting"));

		int sslType = item.getIntegerWithDefault("ssl_type", IncomingItem.TLS);
		boolean sslEnabled = item.getBoolean("enable_ssl");

		// open a port to the server
		if (sslEnabled && sslType == IncomingItem.IMAPS_POP3S) {
			try {
				protocol.openSSLPort();
				usingSSL = true;
			} catch (SSLException e) {
				int result = showErrorDialog(MailResourceLoader.getString(
						"dialog", "error", "ssl_handshake_error")
						+ ": "
						+ e.getLocalizedMessage()
						+ "\n"
						+ MailResourceLoader.getString("dialog", "error",
								"ssl_turn_off"));

				if (result == 1) {
					throw new CommandCancelledException();
				}

				// turn off SSL for the future
				item.setBoolean("enable_ssl", false);
				item.setInteger("port", IMAPProtocol.DEFAULT_PORT);

				// reopen the port
				protocol.openPort();
			}
		} else {
			protocol.openPort();
		}

		// shall we switch to SSL?
		if (!usingSSL && sslEnabled && sslType == IncomingItem.TLS) {
			// if CAPA was not support just give it a try...
			if (isSupported("STLS") || (capabilities.length == 0)) {
				try {
					protocol.startTLS();

					usingSSL = true;
					LOG.info("Switched to SSL");
				} catch (IOException e) {
					int result = showErrorDialog(MailResourceLoader.getString(
							"dialog", "error", "ssl_handshake_error")
							+ ": "
							+ e.getLocalizedMessage()
							+ "\n"
							+ MailResourceLoader.getString("dialog", "error",
									"ssl_turn_off"));

					if (result == 1) {
						throw new CommandCancelledException();
					}

					// turn off SSL for the future
					item.setBoolean("enable_ssl", false);

					// reopen the port
					protocol.openPort();
				} catch (IMAPException e) {
					int result = showErrorDialog(MailResourceLoader.getString(
							"dialog", "error", "ssl_not_supported")
							+ "\n"
							+ MailResourceLoader.getString("dialog", "error",
									"ssl_turn_off"));

					if (result == 1) {
						throw new CommandCancelledException();
					}

					// turn off SSL for the future
					item.setBoolean("enable_ssl", false);
				}
			} else {
				// CAPAs say that SSL is not supported
				int result = showErrorDialog(MailResourceLoader.getString(
						"dialog", "error", "ssl_not_supported")
						+ "\n"
						+ MailResourceLoader.getString("dialog", "error",
								"ssl_turn_off"));

				if (result == 1) {
					throw new CommandCancelledException();
				}

				// turn off SSL for the future
				item.setBoolean("enable_ssl", false);
			}
		}

	}

	public List checkSupportedAuthenticationMethods() throws IOException {

		ArrayList supportedMechanisms = new ArrayList();
		// LOGIN is always supported
		supportedMechanisms.add(new Integer(AuthenticationManager.LOGIN));

		try {
			String serverSaslMechansims[] = getCapas("AUTH");
			// combine them to one string
			StringBuffer oneLine = new StringBuffer("AUTH");
			for (int i = 0; i < serverSaslMechansims.length; i++) {
				oneLine.append(' ');
				oneLine.append(serverSaslMechansims[i].substring(5)); // remove
				// the
				// 'AUTH='
			}

			// AUTH?
			if (serverSaslMechansims != null) {
				List authMechanisms = AuthenticationFactory.getInstance()
						.getSupportedMechanisms(oneLine.toString());
				Iterator it = authMechanisms.iterator();
				while (it.hasNext()) {
					supportedMechanisms.add(new Integer(AuthenticationManager
							.getSaslCode((String) it.next())));
				}
			}
		} catch (IOException e) {
		}

		return supportedMechanisms;
	}

	/**
	 * @param command
	 * @return
	 */
	private String[] getCapas(String command) throws IOException {
		fetchCapas();
		ArrayList list = new ArrayList();

		for (int i = 0; i < capabilities.length; i++) {
			if (capabilities[i].startsWith(command)) {
				list.add(capabilities[i]);
			}
		}

		return (String[]) list.toArray(new String[0]);
	}

	/**
	 * @param command
	 * @return
	 */
	public boolean isSupported(String command) throws IOException {
		fetchCapas();

		for (int i = 0; i < capabilities.length; i++) {
			if (capabilities[i].startsWith(command)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * @throws IOException
	 */
	private void fetchCapas() throws IOException {
		if (capabilities == null) {
			try {
				ensureConnectedState();

				capabilities = protocol.capability();
			} catch (IMAPException e) {
				// CAPA not supported
				capabilities = new String[0];
			} catch (CommandCancelledException e) {

			}
		}
	}

	/**
	 * Gets the selected Authentication method or else the most secure.
	 * 
	 * @return the authentication method
	 */
	private int getLoginMethod() throws CommandCancelledException, IOException {
		String loginMethod = item.get("login_method");
		int result = 0;

		try {
			result = Integer.parseInt(loginMethod);
		} catch (NumberFormatException e) {
			// Just use the default as fallback
		}

		if (result == 0) {
			List supported = checkSupportedAuthenticationMethods();

			if (usingSSL) {
				// NOTE if SSL is possible we just need the plain login
				// since SSL does the encryption for us.
				result = ((Integer) supported.get(0)).intValue();
			} else {
				Collections.sort(supported,
						new AuthenticationSecurityComparator());
				result = ((Integer) supported.get(supported.size() - 1))
						.intValue();
			}

		}

		return result;
	}

	/**
	 * Login to IMAP server.
	 * <p>
	 * Ask user for password.
	 * 
	 * TODO (@author tstich): cleanup if all these ugly if, else cases
	 * 
	 * @throws Exception
	 */
	private void login() throws IOException, IMAPException,
			CommandCancelledException {
		PasswordDialog dialog = new PasswordDialog();
		ensureConnectedState();

		boolean authenticated = false;
		boolean first = true;

		char[] password = new char[0];

		printStatusMessage(MailResourceLoader.getString("statusbar", "message",
				"authenticating"));

		int loginMethod = getLoginMethod();

		// Try to get Password from Configuration
		if (item.get("password").length() != 0) {
			password = Blowfish.decrypt(item.get("password"));
		}
		// Login loop until authenticated
		while (!authenticated) {
			// On the first try check if we need to show the password dialog
			// -> not necessary when password was stored
			if (!first || password.length == 0) {
				// Show the password dialog
				dialog.showDialog(MessageFormat.format(MailResourceLoader
						.getString("dialog", "password", "enter_password"),
						new Object[] { item.get("user"), item.get("host") }),
						new String(password), item.getBoolean("save_password"));
				if (dialog.success()) {
					// User pressed OK
					password = dialog.getPassword();

					// Save or Clear the password in the configuration
					item.setBoolean("save_password", dialog.getSave());
					if (dialog.getSave()) {
						item.setString("password", Blowfish.encrypt(password));
					} else {
						item.setString("password", "");
					}
				} else {
					// User cancelled authentication

					throw new CommandCancelledException();
				}
			}

			// From this point we have a username and password
			// from configuration of from the dialog

			try {
				if (loginMethod == AuthenticationManager.LOGIN) {
					protocol.login(item.get("user"), password);

					// If no exception happened we have successfully logged
					// in
					authenticated = true;
				} else {
					try {
						// AUTH
						protocol.authenticate(AuthenticationManager
								.getSaslName(loginMethod), item.get("user"),
								password);

						// If no exception happened we have successfully logged
						// in
						authenticated = true;
					} catch (AuthenticationException e) {
						// If the cause is a IMAPExcpetion then only password
						// wrong
						// else bogus authentication mechanism
						if (e.getCause() instanceof IMAPException)
							throw (IMAPException) e.getCause();

						// Some error in the client/server communication
						// --> fall back to default login process
						int result = JOptionPane
								.showConfirmDialog(
										FrameManager.getInstance()
												.getActiveFrame(),
										new MultiLineLabel(
												e.getMessage()
														+ "\n"
														+ MailResourceLoader
																.getString(
																		"dialog",
																		"error",
																		"authentication_fallback_to_default")),
										MailResourceLoader.getString("dialog",
												"error",
												"authentication_process_error"),
										JOptionPane.OK_CANCEL_OPTION);

						if (result == JOptionPane.OK_OPTION) {
							loginMethod = AuthenticationManager.LOGIN;
							item.setString("login_method", Integer
									.toString(loginMethod));
						} else {
							throw new CommandCancelledException();
						}
					}
				}

			} catch (IMAPException ex) {
				// login failed?
				IMAPResponse response = ex.getResponse();
				if (response == null || !response.isNO()) {
					// This exception is not because wrong username or
					// password
					throw ex;
				}
			}
			first = false;
		}

		// Sync subscribed folders if this is the first login
		// in this session
		if (firstLogin) {
			if( firstLoginAction != null) {
				firstLoginAction.actionPerformed();
			}
		}

		firstLogin = false;
	}

	public void setFirstLoginAction( IFirstLoginAction action) {
		this.firstLoginAction = action;		
	}
	
	/**
	 * Check if mailbox is already selected.
	 * <p>
	 * If its not selected -> select it.
	 * 
	 * @param path
	 *            mailbox path
	 * @throws Exception
	 */
	public void ensureSelectedState(IMAPFolder folder) throws IOException,
			IMAPException, CommandCancelledException {
		// ensure that we are logged in already
		ensureLoginState();
		String path = folder.getImapPath();

		// if mailbox is not already selected select it
		if (protocol.getState() != IMAPProtocol.SELECTED
				|| !protocol.getSelectedMailbox().equals(path)) {

			printStatusMessage(MessageFormat.format(MailResourceLoader
					.getString("statusbar", "message", "select"),
					new Object[] { folder.getName() }));

			// Here we get the new mailboxinfo for the folder
			messageFolderInfo = protocol.select(path);

			// Set the readOnly flag
			folder.setReadOnly(!messageFolderInfo.isWriteAccess());

			// Convert to a MailboxStatus
			selectedStatus = new MailboxStatus(messageFolderInfo);

			selectedFolder = folder;

			// delete any cached information
			aktMimeTree = null;
			aktMessageUid = null;
		}
	}

	public MailboxStatus getStatus(IMAPFolder folder) throws IOException,
			IMAPException, CommandCancelledException {
		ensureLoginState();

		if (selectedFolder != null && selectedFolder.equals(folder)) {
			// We don't need to issue a additional NOOP
			// here since the ensureLogin() call above
			// ensures also the correct Status in a 
			// MIN_IDLE interval timeframe.
			
			return selectedStatus;
		}
		
		if( selectedFolder == null || protocol.getState() < IMAPProtocol.SELECTED) {
			// if none selected select this folder instead of getting the status
			ensureSelectedState(folder);
			return selectedStatus;
		}
		
		printStatusMessage(MessageFormat.format(MailResourceLoader.getString(
				"statusbar", "message", "status"), new Object[] { folder
				.getName() }));

		return protocol.status(folder.getImapPath(), new String[] { "MESSAGES",
				"UIDNEXT", "RECENT", "UNSEEN", "UIDVALIDITY" });
	}

	/**
	 * Fetch delimiter.
	 * 
	 */
	protected String fetchDelimiter() throws IOException, IMAPException,
			CommandCancelledException {
		// make sure we are already logged in
		ensureLoginState();

		try {
			ListInfo[] listInfo = protocol.list("", "");
			return listInfo[0].getDelimiter();
		} catch (IMAPDisconnectedException e1) {
			ListInfo[] listInfo = protocol.list("", "");
			return listInfo[0].getDelimiter();
		}
	}

	/**
	 * List available Mailboxes.
	 * 
	 * @param reference
	 * @param pattern
	 * @return
	 * @throws Exception
	 */
	public ListInfo[] list(String reference, String pattern) throws Exception {
		ensureLoginState();

		try {
			return protocol.list(reference, pattern);
		} catch (IMAPDisconnectedException e) {
			return protocol.list(reference, pattern);
		}
	}

	/**
	 * Append message to mailbox.
	 * 
	 * @param messageSource
	 *            message source
	 * @param folder
	 *            name of mailbox
	 * 
	 * @throws Exception
	 */
	public Integer append(InputStream messageSource, IMAPFlags flags,
			IMAPFolder folder) throws Exception {
		// make sure we are already logged in
		ensureLoginState();

		// close the mailbox if it is selected
		if (protocol.getState() == IMAPProtocol.SELECTED
				&& protocol.getSelectedMailbox().equals(folder)) {
			protocol.close();
		}

		MailboxStatus status = protocol.status(folder.getImapPath(),
				new String[] { "UIDNEXT" });

		if (flags != null) {
			protocol.append(folder.getImapPath(), messageSource,
					new Object[] { flags });
		} else {
			protocol.append(folder.getImapPath(), messageSource);

		}

		return new Integer((int) status.getUidNext());
	}

	/**
	 * Append message to mailbox.
	 * 
	 * @param messageSource
	 *            message source
	 * @param folder
	 *            name of mailbox
	 * 
	 * @throws Exception
	 */
	public Integer append(InputStream messageSource, IMAPFolder folder)
			throws Exception {
		return append(messageSource, null, folder);
	}

	/**
	 * Create new mailbox.
	 * 
	 * @param mailboxName
	 *            name of new mailbox
	 * 
	 * @return
	 * @throws Exception
	 */
	public void createMailbox(String mailboxName, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		// make sure we are logged in
		ensureLoginState();

		// concate the full name of the new mailbox
		String fullName;
		String path = (folder == null ? "" : folder.getImapPath());

		if (path.length() > 0)
			fullName = path + getDelimiter() + mailboxName;
		else
			fullName = mailboxName;

		// check if the mailbox already exists -> subscribe only
		if (protocol.list("", fullName).length == 0) {
			// create the mailbox on the server
			protocol.create(fullName);
		}

		// subscribe to the new mailbox
		protocol.subscribe(fullName);
	}

	/**
	 * Delete mailbox.
	 * 
	 * @param mailboxName
	 *            name of mailbox
	 * @return
	 * @throws Exception
	 */
	public void deleteFolder(String path) throws Exception {
		// make sure we are already logged in
		ensureLoginState();

		if (protocol.getState() == IMAPProtocol.SELECTED
				&& protocol.getSelectedMailbox().equals(path)) {
			protocol.close();
		}

		protocol.unsubscribe(path);

		protocol.delete(path);
	}

	/**
	 * Rename mailbox.
	 * 
	 * @param oldMailboxName
	 *            old mailbox name
	 * @param newMailboxName
	 *            new mailbox name
	 * @return
	 * @throws Exception
	 */
	public void renameFolder(String oldMailboxName, String newMailboxName)
			throws IOException, IMAPException, CommandCancelledException {
		// make sure we are already logged in
		ensureLoginState();
		protocol.rename(oldMailboxName, newMailboxName);
		protocol.unsubscribe(oldMailboxName);
		protocol.subscribe(newMailboxName);
	}

	/**
	 * Subscribe to mailbox.
	 * 
	 * @param mailboxName
	 *            name of mailbox
	 * @return
	 * @throws Exception
	 */
	public void subscribeFolder(String mailboxName) throws IOException,
			IMAPException, CommandCancelledException {
		// make sure we are already logged in
		ensureLoginState();

		protocol.subscribe(mailboxName);
	}

	/**
	 * Unsubscribe to mailbox.
	 * 
	 * @param mailboxNamename
	 *            of mailbox
	 * @return
	 * @throws Exception
	 */
	public void unsubscribeFolder(String mailboxName) throws IOException,
			IMAPException, CommandCancelledException {
		// make sure we are already logged in
		ensureLoginState();

		protocol.unsubscribe(mailboxName);
	}

	/**
	 * Expunge folder.
	 * <p>
	 * Delete every message mark as expunged.
	 * 
	 * @param folder
	 *            name of mailbox
	 * @return
	 * @throws Exception
	 */
	public void expunge(IMAPFolder folder) throws IOException, IMAPException,
			CommandCancelledException {
		ensureSelectedState(folder);

		updatesEnabled = false;
		protocol.expunge();
		updatesEnabled = true;
	}

	/**
	 * Copy a set of messages to another mailbox on the same IMAP server.
	 * <p>
	 * <p>
	 * We copy messages in pieces of 100 headers. This means we tokenize the
	 * <code>list</code> in sublists of the size of 100. Then we execute the
	 * command and process those 100 results.
	 * 
	 * @param destFolder
	 *            destination mailbox
	 * @param uids
	 *            UIDs of messages -> this array will get sorted!
	 * @param path
	 *            source mailbox
	 * @throws Exception
	 */
	public Integer[] copy(IMAPFolder destFolder, Object[] uids,
			IMAPFolder folder) throws Exception {

		ensureSelectedState(folder);

		// We need to sort the uids in order
		// to have the correct association
		// between the new and old uid
		List sortedUids = Arrays.asList(uids);
		Collections.sort(sortedUids);

		MailboxStatus statusBefore = protocol.status(destFolder.getImapPath(),
				new String[] { "UIDNEXT" });

		protocol.uidCopy(new SequenceSet(Arrays.asList(uids)), destFolder
				.getImapPath());

		MailboxStatus statusAfter = protocol.status(destFolder.getImapPath(),
				new String[] { "UIDNEXT" });

		// the UIDS start UIDNext till UIDNext + uids.length
		int copied = (int) (statusAfter.getUidNext() - statusBefore
				.getUidNext());
		Integer[] destUids = new Integer[copied];
		for (int i = 0; i < copied; i++) {
			destUids[i] = new Integer((int) (statusBefore.getUidNext() + i));
		}

		return destUids;
	}

	/**
	 * Fetch the uid for the index.
	 * 
	 * @param index of the message
	 * @param folder the IMAP mailbox
	 * @return uid of the message
	 * @throws IOException
	 * @throws IMAPException
	 * @throws CommandCancelledException
	 */
	public int fetchUid( SequenceSet set, IMAPFolder folder ) throws IOException, IMAPException, CommandCancelledException {
		ensureSelectedState(folder);
		Integer[] result = protocol.fetchUid(set); 
		if( result.length == 1)
			return result[0].intValue();
		else
			return -1;
				
	}
	
	/**
	 * Fetch list of UIDs.
	 * 
	 * @param folder
	 *            mailbox name
	 * 
	 * @return list of flags
	 * @throws Exception
	 */
	public Integer[] fetchUids(SequenceSet set, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		IStatusObservable observable = getObservable();
		printStatusMessage(MailResourceLoader.getString("statusbar", "message",
				"fetch_uid_list"));

		ensureSelectedState(folder);
		if (messageFolderInfo.getExists() > 0) {
			SequenceSet[] packs = divide(set);
			Integer[] result = new Integer[set.getLength(messageFolderInfo
					.getExists())];

			// update the progress
			if (observable != null) {
				observable.setCurrent(0);
				observable.setMax(result.length);
			}

			int pos = 0;

			for (int i = 0; i < packs.length; i++) {
				int packLength = packs[i].getLength(messageFolderInfo
						.getExists());
				System.arraycopy(protocol.fetchUid(packs[i]), 0, result, pos,
						packLength);
				pos += packLength;

				// update the progress
				if (observable != null) {
					observable.setCurrent(pos);
				}
			}

			return result;
		} else {
			return new Integer[0];
		}
	}

	private SequenceSet[] divide(SequenceSet in) {
		int length = in.getLength(messageFolderInfo.getExists());

		if (length > UID_FETCH_STEPS) {
			int[] decomposed = in.toArray(messageFolderInfo.getExists());

			List result = new ArrayList();
			int pos = 0;
			// divide in packs
			while (decomposed.length - pos > UID_FETCH_STEPS) {
				result.add(new SequenceSet(decomposed, pos, UID_FETCH_STEPS));
				pos += UID_FETCH_STEPS;
			}
			// dont forget the rest
			if (decomposed.length - pos > 0) {
				result.add(new SequenceSet(decomposed, pos, decomposed.length
						- pos));
			}

			return (SequenceSet[]) result.toArray(new SequenceSet[0]);
		} else {
			return new SequenceSet[] { in };
		}

	}

	/**
	 * Fetch list of flags and parse it.
	 * 
	 * @param folder
	 *            mailbox name
	 * 
	 * @return list of flags
	 * @throws Exception
	 */
	public IMAPFlags[] fetchFlagsListStartFrom(int startIdx, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		IStatusObservable observable = getObservable();

		ensureSelectedState(folder);
		if (selectedStatus.getMessages() - startIdx >= 0) {
			SequenceSet set = new SequenceSet();
			set.addOpenRange(startIdx);

			SequenceSet[] packs = divide(set);

			// update the progress
			if (observable != null) {
				observable.setCurrent(0);
				observable.setMax(set.getLength(selectedStatus.getMessages()));
			}

			List allResults = new ArrayList(packs.length);

			int pos = 0;

			// store the intermediate results in a list
			for (int i = 0; i < packs.length; i++) {
				try {
					IMAPFlags[] r = protocol.fetchFlags(packs[i]);
					pos += r.length;

					allResults.add(r);
				} catch (IMAPException e) {
					// Entry does not exist on server
					// -> add nothing
				}

				// update the progress
				if (observable != null) {
					observable.setCurrent(pos);
				}
			}

			// Combine the results in one array
			IMAPFlags[] result = new IMAPFlags[pos];
			Iterator it = allResults.iterator();

			pos = 0;
			while (it.hasNext()) {
				IMAPFlags[] r = (IMAPFlags[]) it.next();
				System.arraycopy(r, 0, result, pos, r.length);

				pos += r.length;
			}

			return result;
		} else {
			return new IMAPFlags[0];
		}
	}

	public NamespaceCollection fetchNamespaces() throws IOException,
			IMAPException, CommandCancelledException {
		ensureLoginState();
		return protocol.namespace();
	}

	/**
	 * Fetch list of headers and parse them.
	 * <p>
	 * We fetch headers in pieces of 100 headers. This means we tokenize the
	 * <code>list</code> in sublists of the size of 100. Then we execute the
	 * command and process those 100 results.
	 * 
	 * @param headerList
	 *            headerlist to add new headers
	 * @param list
	 *            list of UIDs to download
	 * @param path
	 *            mailbox name
	 * @throws Exception
	 */
	public void fetchHeaderList(IHeaderList headerList, List list,
			IMAPFolder folder) throws Exception {
		// make sure this mailbox is selected
		ensureSelectedState(folder);

		printStatusMessage(MailResourceLoader.getString("statusbar", "message",
				"fetch_header_list"));

		int count = list.size() / IMAPServer.STEP_SIZE;
		int rest = list.size() % IMAPServer.STEP_SIZE;
		getObservable().setCurrent(0);
		getObservable().setMax(count + 1);
		for (int i = 0; i < count; i++) {
			doFetchHeaderList(headerList, list.subList(
					i * IMAPServer.STEP_SIZE, (i + 1) * IMAPServer.STEP_SIZE));
			getObservable().setCurrent(i);
		}

		if (rest > 0) {
			doFetchHeaderList(headerList, list
					.subList(count * IMAPServer.STEP_SIZE, count
							* IMAPServer.STEP_SIZE + rest));
		}

		getObservable().setCurrent(count + 1);
	}

	/**
	 * @param headerList
	 * @param list
	 * @throws IOException
	 * @throws IMAPException
	 */
	private void doFetchHeaderList(IHeaderList headerList, List list)
			throws IOException, IMAPException {
		// get list of user-defined headerfields
		String[] headerFields = CachedHeaderfields.getDefaultHeaderfields();

		IMAPHeader[] headers = protocol.uidFetchHeaderFields(new SequenceSet(
				list), headerFields);

		for (int i = 0; i < headers.length; i++) {
			// add it to the headerlist
			ColumbaHeader header = new ColumbaHeader(headers[i].getHeader());
			Object uid = headers[i].getUid();

			header.getAttributes().put("columba.uid", uid);
			header.getAttributes().put("columba.size", headers[i].getSize());
			header.getAttributes().put("columba.accountuid", getAccountUid());

			// set the attachment flag
			header.getAttributes().put("columba.attachment",
					header.hasAttachments());

			// make sure that we have a Message-ID
			String messageID = (String) header.get("Message-Id");
			if (messageID != null)
				header.set("Message-ID", header.get("Message-Id"));

			headerList.add(header, uid);
		}
	}

	protected Integer getAccountUid() {
		AccountItem accountItem = new AccountItem(item.getRoot().getParent());
		return new Integer(accountItem.getInteger("uid"));
	}

	protected synchronized void ensureConnectedState() throws CommandCancelledException,
			IOException, IMAPException {
		if (Math.abs(System.currentTimeMillis() - lastCommunication) > MIN_IDLE) {
			try {
				protocol.noop();
			} catch (IOException e) {
				// Now the state of the procotol is more certain correct
			} catch (IMAPDisconnectedException e) {

			}
		}

		if (protocol.getState() < IMAPProtocol.NON_AUTHENTICATED) {
			printStatusMessage(MailResourceLoader
					.getString("statusbar", "message", "connecting"));
			openConnection();
		}

		// update this point of time as last communication
		// since every functio calls this before communicating with
		// the server
		lastCommunication = System.currentTimeMillis();
	}

	/**
	 * Ensure that we are in login state.
	 * 
	 * @throws Exception
	 */
	protected void ensureLoginState() throws IOException, IMAPException,
			CommandCancelledException {
		ensureConnectedState();

		if (protocol.getState() < IMAPProtocol.AUTHENTICATED) {
			printStatusMessage(MailResourceLoader
					.getString("statusbar", "message", "authenticating"));
			login();
		}
	}

	/**
	 * Get {@link MimeTree}.
	 * 
	 * @param uid
	 *            message UID
	 * @param folder
	 *            mailbox name
	 * @return mimetree
	 * @throws Exception
	 */
	public MimeTree getMimeTree(Object uid, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			// Use a caching mechanism for this
			if (aktMimeTree == null || !aktMessageUid.equals(uid)) {
				aktMimeTree = protocol.uidFetchBodystructure(((Integer) uid)
						.intValue());
				aktMessageUid = uid;
			}

			return aktMimeTree;
		} catch (IMAPDisconnectedException e) {
			return getMimeTree(uid, folder);
		}
	}

	/**
	 * Get {@link MimePart}.
	 * 
	 * @param uid
	 *            message UID
	 * @param address
	 *            address of MimePart in MimeTree
	 * @param folder
	 *            mailbox name
	 * @return mimepart
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 * @throws Exception
	 */
	public InputStream getMimePartBodyStream(Object uid, Integer[] address,
			IMAPFolder folder) throws IOException, IMAPException,
			CommandCancelledException {
		try {
			ensureSelectedState(folder);

			return protocol.uidFetchBody(((Integer) uid).intValue(), address);
		} catch (IMAPDisconnectedException e) {
			return getMimePartBodyStream(uid, address, folder);
		}
	}

	/**
	 * Get {@link MimePart}.
	 * 
	 * @param uid
	 *            message UID
	 * @param address
	 *            address of MimePart in MimeTree
	 * @param folder
	 *            mailbox name
	 * @return mimepart
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 * @throws Exception
	 */
	public Header getHeaders(Object uid, String[] keys, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			IMAPHeader[] headers = protocol.uidFetchHeaderFields(
					new SequenceSet(((Integer) uid).intValue()), keys);

			return headers[0].getHeader();
		} catch (IMAPDisconnectedException e) {
			return getHeaders(uid, keys, folder);
		}
	}

	/**
	 * Get complete headers.
	 * 
	 * @param uid
	 *            message uid
	 * @param folder
	 *            mailbox path
	 * @return
	 * @throws Exception
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 */
	public Header getAllHeaders(Object uid, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			IMAPHeader[] headers = protocol.uidFetchHeader(new SequenceSet(
					((Integer) uid).intValue()));

			return headers[0].getHeader();
		} catch (IMAPDisconnectedException e) {
			return getAllHeaders(uid, folder);
		}
	}

	/**
	 * Get {@link MimePart}.
	 * 
	 * @param uid
	 *            message UID
	 * @param address
	 *            address of MimePart in MimeTree
	 * @param folder
	 *            mailbox name
	 * @return mimepart
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 * @throws Exception
	 */
	public InputStream getMimePartSourceStream(Object uid, Integer[] address,
			IMAPFolder folder) throws IOException, IMAPException,
			CommandCancelledException {
		try {
			ensureSelectedState(folder);

			InputStream headerSource = protocol.uidFetchMimeHeaderSource(
					((Integer) uid).intValue(), address);
			InputStream bodySource = protocol.uidFetchBody(((Integer) uid)
					.intValue(), address);

			return new SequenceInputStream(headerSource, bodySource);
		} catch (IMAPDisconnectedException e) {
			return getMimePartSourceStream(uid, address, folder);
		}
	}

	/**
	 * Get complete message source.
	 * 
	 * @param uid
	 *            message UID
	 * @param path
	 *            mailbox name
	 * @return message source
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 * @throws Exception
	 */
	public InputStream getMessageSourceStream(Object uid, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			return protocol.uidFetchMessage(((Integer) uid).intValue());
		} catch (IMAPDisconnectedException e) {
			return getMessageSourceStream(uid, folder);
		}
	}

	/**
	 * Mark message as specified by variant.
	 * <p>
	 * See {@link MarkMessageCommand}for a list of variants.
	 * <p>
	 * We mark messages in pieces of 100 headers. This means we tokenize the
	 * <code>list</code> in sublists of the size of 100. Then we execute the
	 * command and process those 100 results.
	 * 
	 * @param uids
	 *            message UID
	 * @param variant
	 *            variant (read/flagged/expunged/etc.)
	 * @param folder
	 *            mailbox name
	 * @throws Exception
	 */
	public void markMessage(Object[] uids, int variant, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			SequenceSet uidSet = new SequenceSet(Arrays.asList(uids));

			protocol.uidStore(uidSet, variant > 0, convertToFlags(variant));
		} catch (IMAPDisconnectedException e) {
			markMessage(uids, variant, folder);
		}
	}

	public void setFlags(Object[] uids, IMAPFlags flags, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);
			SequenceSet uidSet = new SequenceSet(Arrays.asList(uids));

			protocol.uidStore(uidSet, true, flags);
		} catch (IMAPDisconnectedException e) {
			setFlags(uids, flags, folder);
		}
	}

	/**
	 * Search messages.
	 * 
	 * @param uids
	 *            message UIDs
	 * @param filterRule
	 *            filter rules
	 * @param folder
	 *            mailbox name
	 * @return list of UIDs which match filter rules
	 * @throws Exception
	 */
	public List search(Object[] uids, FilterRule filterRule, IMAPFolder folder)
			throws Exception {
		LinkedList result = new LinkedList(search(filterRule, folder));

		ListTools.intersect(result, Arrays.asList(uids));

		return result;
	}

	public int getIndex(Integer uid, IMAPFolder folder) throws IOException,
			IMAPException, CommandCancelledException {

		try {
			ensureSelectedState(folder);

			SearchKey key = new SearchKey(SearchKey.UID, uid);

			Integer[] index = protocol.search(new SearchKey[] { key });
			if (index.length > 0) {
				return index[0].intValue();
			} else {
				return -1;
			}
		} catch (IMAPDisconnectedException e) {
			return getIndex(uid, folder);
		}
	}

	public Integer[] search(SearchKey key, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		try {
			ensureSelectedState(folder);

			return protocol.uidSearch(new SearchKey[] { key });
		} catch (IMAPDisconnectedException e) {
			return search(key, folder);
		}
	}

	/**
	 * 
	 * @param filterRule
	 * @param folder
	 * @return
	 * @throws Exception
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 */
	public List search(FilterRule filterRule, IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {

		try {
			ensureSelectedState(folder);

			SearchKey[] searchRequest;

			searchRequest = createSearchKey(filterRule);

			Integer[] result = null;
			Charset charset = UTF8;

			while (result == null) {
				try {
					result = protocol.uidSearch(charset, searchRequest);
				} catch (IMAPException e) {
					if (e.getResponse().isNO() && charset != null) {
						// Server does not support UTF-8
						// -> fall back to System default
						if ( charset.equals(UTF8)) {
							charset = DEFAULT;
						} else if (charset == DEFAULT) {
							// If this also does not work
							// -> fall back to no charset specified
							charset = null;
						} else {
							// something else is wrong
							throw e;
						}
					} else
						throw e;
				}
			}

			return Arrays.asList(result);
		} catch (IMAPDisconnectedException e) {
			return search(filterRule, folder);
		}
	}

	/**
	 * @param filterRule
	 */
	private SearchKey[] createSearchKey(FilterRule filterRule) {
		SearchKey[] searchRequest;
		int argumentSize = filterRule.getChildCount();
		// One or many arguments?
		if (argumentSize == 1) {
			// One is the easiest case
			searchRequest = new SearchKey[] { getSearchKey(filterRule.get(0)) };
		} else {
			// AND or OR ? -> AND is implicit, OR must be specified
			if (filterRule.getConditionInt() == FilterRule.MATCH_ALL) {
				// AND : simply create a list of arguments
				searchRequest = new SearchKey[argumentSize];

				for (int i = 0; i < argumentSize; i++) {
					searchRequest[i] = getSearchKey(filterRule.get(i));
				}

			} else {
				// OR : the arguments must be glued by a OR SearchKey
				SearchKey orKey;

				orKey = new SearchKey(SearchKey.OR, getSearchKey(filterRule
						.get(argumentSize - 1)), getSearchKey(filterRule
						.get(argumentSize - 2)));

				for (int i = argumentSize - 3; i >= 0; i--) {
					orKey = new SearchKey(SearchKey.OR, getSearchKey(filterRule
							.get(i)), orKey);
				}

				searchRequest = new SearchKey[] { orKey };
			}
		}

		return searchRequest;
	}

	/**
	 * @param criteria
	 * @return
	 */
	private SearchKey getSearchKey(FilterCriteria criteria) {
		int operator = criteria.getCriteria();
		int type = new MailFilterCriteria(criteria).getType();

		switch (type) {
		case MailFilterCriteria.FROM: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.FROM, criteria
						.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.FROM, criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.CC: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.CC, criteria.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(SearchKey.CC,
						criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.BCC: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.BCC, criteria.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.BCC, criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.TO: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.TO, criteria.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(SearchKey.TO,
						criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.SUBJECT: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.SUBJECT, criteria
						.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.SUBJECT, criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.BODY: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.BODY, criteria
						.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.BODY, criteria.getPatternString()));
			}
		}

		case MailFilterCriteria.CUSTOM_HEADERFIELD: {
			if (operator == FilterCriteria.CONTAINS) {
				return new SearchKey(SearchKey.HEADER, new MailFilterCriteria(
						criteria).getHeaderfieldString(), criteria
						.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.HEADER, new MailFilterCriteria(criteria)
								.getHeaderfieldString(), criteria
								.getPatternString()));
			}
		}

		case MailFilterCriteria.DATE: {
			DateFormat df = DateFormat.getDateInstance();

			IMAPDate searchPattern = null;

			try {
				searchPattern = new IMAPDate(df.parse(criteria
						.getPatternString()));
			} catch (java.text.ParseException ex) {
				// should never happen
				ex.printStackTrace();
			}

			if (operator == FilterCriteria.DATE_BEFORE) {
				return new SearchKey(SearchKey.BEFORE, searchPattern);
			} else {
				// AFTER
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.BEFORE, searchPattern));
			}
		}

		case MailFilterCriteria.SIZE: {
			if (operator == FilterCriteria.SIZE_SMALLER) {
				return new SearchKey(SearchKey.SMALLER, criteria
						.getPatternString());
			} else {
				// contains not
				return new SearchKey(SearchKey.NOT, new SearchKey(
						SearchKey.SMALLER, criteria.getPatternString()));
			}
		}
		}

		return null;
	}

	/**
	 * Check if string contains US-ASCII characters.
	 * 
	 * @param s
	 * @return true, if string contains US-ASCII characters
	 */
	protected static boolean isAscii(String s) {
		int l = s.length();

		for (int i = 0; i < l; i++) {
			if ((int) s.charAt(i) > 0177) { // non-ascii

				return false;
			}
		}

		return true;
	}

	/**
	 * Create string representation of {@ link MarkMessageCommand}constants.
	 * 
	 * @param variant
	 * @return
	 */
	private IMAPFlags convertToFlags(int variant) {
		IMAPFlags result = new IMAPFlags();

		switch (variant) {
		case MarkMessageCommand.MARK_AS_READ:
		case MarkMessageCommand.MARK_AS_UNREAD: {
			result.setSeen(true);

			break;
		}

		case MarkMessageCommand.MARK_AS_FLAGGED:
		case MarkMessageCommand.MARK_AS_UNFLAGGED: {
			result.setFlagged(true);

			break;
		}

		case MarkMessageCommand.MARK_AS_EXPUNGED:
		case MarkMessageCommand.MARK_AS_UNEXPUNGED: {
			result.setDeleted(true);

			break;
		}

		case MarkMessageCommand.MARK_AS_ANSWERED: {
			result.setAnswered(true);

			break;
		}

		case MarkMessageCommand.MARK_AS_SPAM:
		case MarkMessageCommand.MARK_AS_NOTSPAM: {
			result.setJunk(true);

			break;
		}
		case MarkMessageCommand.MARK_AS_DRAFT: {
			result.setDraft(true);

			break;
		}
		}

		return result;
	}

	/**
	 * @param folder
	 *            TODO
	 * @return
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 * @throws IOException
	 */
	public MailboxInfo getMessageFolderInfo(IMAPFolder folder)
			throws IOException, IMAPException, CommandCancelledException {
		ensureSelectedState(folder);

		return messageFolderInfo;
	}

	/**
	 * @return
	 */
	public ListInfo[] fetchSubscribedFolders() throws IOException,
			IMAPException, CommandCancelledException {
		try {
			ensureLoginState();
			ListInfo[] lsub = protocol.lsub("", "*");

			// Also set the delimiter
			if (lsub.length > 0) {
				delimiter = lsub[0].getDelimiter();
			}

			return lsub;
		} catch (IMAPDisconnectedException e) {
			return fetchSubscribedFolders();
		}
	}

	/**
	 * @param imapPath
	 * @return
	 * @throws IOException
	 * @throws CommandCancelledException
	 * @throws IMAPException
	 */
	public boolean isSelected(IMAPFolder folder) throws IOException,
			IMAPException, CommandCancelledException {
		ensureLoginState();

		return (protocol.getState() == IMAPProtocol.SELECTED && protocol
				.getSelectedMailbox().equals(folder.getImapPath()));
	}

	/**
	 * @param e
	 * @return
	 */
	private int showErrorDialog(String message) {
		Object[] options = new String[] {
				MailResourceLoader.getString("", "global", "ok").replaceAll(
						"&", ""),
				MailResourceLoader.getString("", "global", "cancel")
						.replaceAll("&", "") };

		int result = JOptionPane.showOptionDialog(null, message, "Warning",
				JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
				options, options[0]);
		return result;
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#alertMessage(java.lang.String)
	 */
	public void alertMessage(String arg0) {
		// TODO: Show dialog
		LOG.warning(arg0);
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#connectionClosed(java.lang.String,
	 *      java.lang.String)
	 */
	public void connectionClosed(String arg0, String arg1) {
		LOG.info(arg0);
		selectedFolder = null;
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#existsChanged(java.lang.String,
	 *      int)
	 */
	public void existsChanged(String arg0, int arg1) {
		//int newMessages = arg1 - selectedStatus.getMessages();
		selectedStatus.setMessages(arg1);
		/*
		if( newMessages > 0 ) {
			selectedStatus.setRecent(selectedStatus.getRecent() + newMessages);
		}*/
		
		if (updatesEnabled) {
			if( existsChangedAction != null) {
				existsChangedAction.actionPerformed(selectedFolder);
			}
			
			LOG.fine("Exists changed -> triggering update");

		}
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#flagsChanged(java.lang.String,
	 *      org.columba.ristretto.imap.IMAPFlags)
	 */
	public void flagsChanged(String arg0, IMAPFlags arg1) {
		LOG.fine("Flag changed -> triggering update");

		if( updateFlagAction != null) {
			updateFlagAction.actionPerformed(selectedFolder, arg1);
		}
		
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#parseError(java.lang.String)
	 */
	public void parseError(String arg0) {
		LOG.warning(arg0);
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#recentChanged(java.lang.String,
	 *      int)
	 */
	public void recentChanged(String arg0, int arg1) {
		// selectedStatus.setRecent(arg1);

		// We trigger an update only when the exists changed
		// which should be equal with a Recent change.
	}

	/**
	 * @see org.columba.ristretto.imap.IMAPListener#warningMessage(java.lang.String)
	 */
	public void warningMessage(String arg0) {
		LOG.warning(arg0);
	}

	/**
	 * @return Returns the item.
	 */
	public ImapItem getItem() {
		return item;
	}

	public void update(Observable o, Object arg) {
		protocol = new IMAPProtocol(item.get("host"), item.getInteger("port"));		
	}

	/**
	 * @param existsChangedAction The existsChangedAction to set.
	 */
	public void setExistsChangedAction(IExistsChangedAction existsChangedAction) {
		this.existsChangedAction = existsChangedAction;
	}

	/**
	 * @param updateFlagAction The updateFlagAction to set.
	 */
	public void setUpdateFlagAction(IUpdateFlagAction updateFlagAction) {
		this.updateFlagAction = updateFlagAction;
	}

	/**
	 * @param observable The observable to set.
	 */
	public void setObservable(IStatusObservable observable) {
		this.observable = observable;
	}
}
