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
package org.columba.mail.gui.table;

import javax.swing.JOptionPane;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.tree.TreePath;

import org.columba.api.exception.PluginHandlerNotFoundException;
import org.columba.api.plugin.IExtension;
import org.columba.core.logging.Logging;
import org.columba.core.plugin.PluginManager;
import org.columba.core.resourceloader.ImageLoader;
import org.columba.mail.gui.table.model.HeaderTableModel;
import org.columba.mail.gui.table.model.MessageNode;
import org.columba.mail.gui.table.model.TableModelSorter;
import org.columba.mail.gui.table.plugins.BasicHeaderRenderer;
import org.columba.mail.gui.table.plugins.BasicRenderer;
import org.columba.mail.gui.table.plugins.BooleanHeaderRenderer;
import org.columba.mail.gui.table.plugins.DefaultLabelRenderer;
import org.columba.mail.plugin.TableRendererExtensionHandler;
import org.frapuccino.treetable.CustomTreeTableCellRenderer;
import org.frapuccino.treetable.TreeTable;

/**
 * This widget is a mix between a JTable and a JTree ( we need the JTree for the
 * Threaded viewing of mailing lists )
 * 
 * @version 0.9.1
 * @author fdietz
 */
public class TableView extends TreeTable {

	private HeaderTableModel headerTableModel;

	private TableRendererExtensionHandler handler;

	private TableModelSorter sorter;

	public TableView(HeaderTableModel headerTableModel, TableModelSorter sorter) {
		super();

		this.sorter = sorter;
		this.headerTableModel = headerTableModel;

		setModel(headerTableModel);

		// load plugin handler used for the columns
		try {
			handler = (TableRendererExtensionHandler) PluginManager.getInstance()
					.getHandler(TableRendererExtensionHandler.NAME);
		} catch (PluginHandlerNotFoundException ex) {
			ex.printStackTrace();
		}

		getTree().setCellRenderer(new SubjectTreeRenderer(this));
	}

	/**
	 * Enable/Disable tree renderer for the subject column.
	 * <p>
	 * Note, that this works because {@link TreeTable}sets its
	 * {@link CustomTreeTableCellRenderer}as default renderer for this class.
	 * <br>
	 * When calling TableModel.getColumnClass(column), the model returns
	 * CustomTreeTableCellRenderer.class, if the threaded- view is enabled.
	 * <p>
	 * JTable automatically falls back to the default renderers, if no custom
	 * renderer is applied. <br>
	 * For this reason, we just remove the custom cell renderer for the
	 * "Subject" column.
	 * 
	 * @param b
	 *            if true, enable tree renderer. False, otherwise
	 */
	public void enableThreadedView(boolean b) {
		if (b) {
			TableColumn tc = null;
			tc = getColumn("Subject");

			// disable subject column renderer, use tree-cellrenderer instead
			tc.setCellRenderer(null);

			// tc.setCellEditor(new CustomTreeTableCellEditor());
		} else {
			TableColumn tc = null;
			try {
				tc = getColumn("Subject");
				// change subject column renderer back to default
				tc.setCellRenderer(new BasicRenderer("columba.subject"));
			} catch (IllegalArgumentException e) {

			}

		}
	}

	/**
	 * Create table column using plugin extension point
	 * <b>org.columba.mail.tablerenderer </b>.
	 * 
	 * @param name
	 *            name of plugin ID
	 * @param size
	 *            size of table column
	 * @return table column object
	 */
	public TableColumn createTableColumn(String name, int size) {
		TableColumn c = new TableColumn();

		// set name of column
		c.setHeaderValue(name);
		c.setIdentifier(name);

		DefaultLabelRenderer r = null;

		if (handler.exists(name)) {
			// load plugin
			try {
				IExtension extension = handler.getExtension(name);
				r = (DefaultLabelRenderer) extension.instanciateExtension(null);
			} catch (Exception e) {
				if (Logging.DEBUG) {
					e.printStackTrace();
				}

				JOptionPane.showMessageDialog(null,
						"Error while loading column: " + name + "\n"
								+ e.getMessage());
			}
		}

		if (r == null) {
			// no specific renderer found
			// -> use default renderer
			r = new BasicRenderer(name);

			registerRenderer(c, name, r, new BasicHeaderRenderer(name, sorter),
					size, false);
		} else {
			IExtension extension = handler.getExtension(name);
			
			String image = extension.getMetadata().getAttribute("icon");
			String fixed = extension.getMetadata().getAttribute( "size");
			boolean lockSize = false;

			if (fixed != null) {
				if (fixed.equals("fixed")) {
					size = 23;
					lockSize = true;
				}
			}

			if (lockSize) {
				registerRenderer(c, name, r, new BooleanHeaderRenderer(
						ImageLoader.getSmallImageIcon(image)), size, lockSize);
			} else {
				registerRenderer(c, name, r, new BasicHeaderRenderer(name,
						sorter), size, lockSize);
			}
		}

		return c;
	}

	/**
	 * Set properties of this column.
	 * 
	 * @param tc
	 *            table column
	 * @param name
	 *            name of table column
	 * @param cell
	 *            cell renderer
	 * @param header
	 *            header renderer
	 * @param size
	 *            width of column
	 * @param lockSize
	 *            is this a fixed size column?
	 */
	protected void registerRenderer(TableColumn tc, String name,
			DefaultLabelRenderer cell, TableCellRenderer header, int size,
			boolean lockSize) {
		if (tc == null) {
			return;
		}

		if (cell != null) {
			tc.setCellRenderer(cell);
		}

		if (header != null) {
			tc.setHeaderRenderer(header);
		}

		if (lockSize) {
			tc.setMaxWidth(size);
			tc.setMinWidth(size);
		} else {
			// Logging.log.info("setting size =" + size);
			tc.setPreferredWidth(size);
		}
	}

	/**
	 * Get selected message node.
	 * 
	 * @return selected message node
	 */
	public MessageNode getSelectedNode() {
		MessageNode node = (MessageNode) getTree()
				.getLastSelectedPathComponent();

		return node;
	}

	/**
	 * Get array of selected message nodes.
	 * 
	 * @return arrary of selected message nodes
	 */
	public MessageNode[] getSelectedNodes() {
		int[] rows = null;
		MessageNode[] nodes = null;

		rows = getSelectedRows();
		nodes = new MessageNode[rows.length];

		for (int i = 0; i < rows.length; i++) {
			TreePath treePath = getTree().getPathForRow(rows[i]);

			if (treePath == null) {
				continue;
			}

			nodes[i] = (MessageNode) treePath.getLastPathComponent();
		}

		return nodes;
	}

	/**
	 * Get message node with UID
	 * 
	 * @param uid
	 *            UID of message node
	 * 
	 * @return message node
	 */
	public MessageNode getMessagNode(Object uid) {
		return headerTableModel.getMessageNode(uid);
	}

	/**
	 * Select first row and make it visible.
	 * 
	 * @return uid of selected row
	 */
	public Object selectFirstRow() {

		Object uid = null;

		// if there are entries in the table
		if (getRowCount() > 0) {
			// changing the selection to the first row
			changeSelection(0, 0, true, false);

			// getting the node
			MessageNode selectedNode = (MessageNode) getValueAt(0, 0);

			// and getting the uid for this node
			uid = selectedNode.getUid();

			// scrolling to the first row
			scrollRectToVisible(getCellRect(0, 0, false));
			requestFocus();

			return uid;
		}

		return null;
	}

	/**
	 * Select last row and make it visible
	 * 
	 * @return uid of selected row
	 */
	public Object selectLastRow() {

		Object uid = null;

		// if there are entries in the table
		if (getRowCount() > 0) {
			// changing the selection to the first row
			changeSelection(getRowCount() - 1, 0, true, false);

			// getting the node
			MessageNode selectedNode = (MessageNode) getValueAt(
					getRowCount() - 1, 0);

			// and getting the uid for this node
			uid = selectedNode.getUid();

			// scrolling to the first row
			scrollRectToVisible(getCellRect(getRowCount() - 1, 0, false));
			requestFocus();

			return uid;
		}

		return null;
	}

	/**
	 * Overwritten, because selectAll doesn't also select the nodes of the
	 * underlying JTree, which aren't expanded and therefore not visible.
	 * <p>
	 * Go through all nodes and expand them. Afterwards select all rows in the
	 * JTable.
	 * 
	 * @see javax.swing.JTable#selectAll()
	 */
	public void selectAll() {
		// expand all rows
		for (int i = 0; i < getRowCount(); i++) {
			TreePath path = getTree().getPathForRow(i);
			getTree().expandPath(path);
		}
		// select all rows
		super.selectAll();
	}

	/**
	 * Scroll table to row and request focus.
	 * 
	 * @param row
	 *            selected row
	 */
	public void makeRowVisible(int row) {
		scrollRectToVisible(getCellRect(row, 0, false));
		requestFocus();
	}

	/**
	 * Change the selection to the specified row
	 * 
	 * @param row
	 *            row to selected
	 */
	public void selectRow(int row) {

		if (getRowCount() > 0) {
			if (row < 0) {
				row = 0;
			}

			if (row >= getRowCount()) {
				row = getRowCount() - 1;
			}

			// changing the selection to the specified row
			// changeSelection(row, 0, true, false);
			changeSelection(row, 0, false, false);

			// getting the node
			MessageNode selectedNode = (MessageNode) getValueAt(row, 0);

			// and getting the uid for this node
			Object uid = selectedNode.getUid();

			makeRowVisible(row);
			/*
			 * // scrolling to the first row
			 * scrollRectToVisible(getCellRect(row, 0, false)); requestFocus();
			 */
		}
	}

	/**
	 * Yes, this was overwritten on purpose. Updating the table-model (swing's
	 * internals - not Columba related ) always triggers an additional call to
	 * clearSelection. This was the easiest place to circumvent this behaviour.
	 * 
	 * @see javax.swing.JTable#clearSelection()
	 */
	public void clearSelection() {
		// don't clear selection
	}
}