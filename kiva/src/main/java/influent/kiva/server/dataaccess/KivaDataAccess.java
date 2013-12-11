/**
 * Copyright (c) 2013 Oculus Info Inc.
 * http://www.oculusinfo.com/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package influent.kiva.server.dataaccess;

import influent.idl.FL_Constraint;
import influent.idl.FL_DataAccess;
import influent.idl.FL_DateRange;
import influent.idl.FL_Entity;
import influent.idl.FL_EntitySearch;
import influent.idl.FL_Link;
import influent.idl.FL_LinkTag;
import influent.idl.FL_ListRange;
import influent.idl.FL_Property;
import influent.idl.FL_PropertyMatchDescriptor;
import influent.idl.FL_PropertyTag;
import influent.idl.FL_PropertyType;
import influent.idl.FL_SearchResult;
import influent.idl.FL_SearchResults;
import influent.idl.FL_SortBy;
import influent.idl.FL_TransactionResults;
import influent.idlhelper.PropertyHelper;
import influent.midtier.TypedId;
import influent.server.dataaccess.DataAccessHelper;
import influent.server.dataaccess.DataNamespaceHandler;
import influent.server.dataaccess.DataViewDataAccess;
import influent.server.utilities.SQLConnectionPool;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.avro.AvroRemoteException;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Hopefully a better implementation of the Kiva Data Access
 * @author msavigny
 *
 */

public class KivaDataAccess extends DataViewDataAccess implements FL_DataAccess {

	public static final String s_kivaTypeKey = "kiva_type";
	private static Logger s_logger = LoggerFactory.getLogger(KivaDataAccess.class);

	
	
	@Inject	
	public KivaDataAccess(
			SQLConnectionPool connectionPool,
			FL_EntitySearch search,
			DataNamespaceHandler namespaceHandler)
			throws ClassNotFoundException, SQLException, JSONException {
		super(connectionPool, search, namespaceHandler);
	}
	
	/**
	 * TODO: push this change down from a list of singleton match descriptors to a single list match descriptor
	 */
	@Override
	public List<FL_Entity> getEntities(List<String> entities) throws AvroRemoteException {
		//Construct the id search query for the search
		
		List<FL_Entity> results = new ArrayList<FL_Entity>();
		
		int qCount = 0;			//How many entities to query at once
		int maxFetch = 100;
		List<Object> idVals = new ArrayList<Object>(maxFetch);
		Iterator<String> idIter = entities.iterator();
		
		while (idIter.hasNext()) {
			
			idVals.add(idIter.next());
			if (qCount == (maxFetch-1) || !idIter.hasNext()) {
				FL_PropertyMatchDescriptor idMatch = FL_PropertyMatchDescriptor.newBuilder()
						.setKey("uid")
						.setRange(FL_ListRange.newBuilder().setType(FL_PropertyType.STRING).setValues(idVals).build())
						.setConstraint(FL_Constraint.REQUIRED_EQUALS)
						.build();
				FL_SearchResults searchResult = _search.search(null, Collections.singletonList(idMatch), 0, maxFetch, null);
				
				s_logger.info("Searched for "+qCount+" ids, found "+searchResult.getTotal());
				
				for (FL_SearchResult r : searchResult.getResults()) {
					FL_Entity fle = (FL_Entity)r.getResult();
					if (entities.contains(fle.getUid())) {
						results.add(fle);
					} else {
						s_logger.error("Got entitiy "+fle.getUid()+" that wasn't in search");
					}
				}
				
				qCount=0;
				idVals.clear();
			} else {
				qCount++;
			}
		}
		
		return results;
	}
	
	
	private static DecimalFormat world_df = new DecimalFormat("#,##0.00;-#,##0.00");
	
	
	
	
	@Override
	public FL_TransactionResults getAllTransactions(
			List<String> entities,
			FL_LinkTag tag,
			FL_DateRange dateRange,
			FL_SortBy sort,
			List<String> linkFilter,
			long start,
			long max) throws AvroRemoteException {
		
		final List<String> ns_entities = TypedId.nativeFromTypedIds(entities);
		final List<FL_Link> links = new ArrayList<FL_Link>();
		final FL_TransactionResults.Builder results = FL_TransactionResults.newBuilder().setResults(links);
		
		try {
			Connection connection = _connectionPool.getConnection();
			Statement stmt = connection.createStatement();
			
			DateTime startDate = DataAccessHelper.getStartDate(dateRange);
			DateTime endDate = DataAccessHelper.getEndDate(dateRange);
			
			// TODO remove me when old dbs using 'Date' have been updated or rebuilt
			String dateColumnName = getNamespaceHandler().tableName(null, "TransactionDate");
			
			String orderBy = "";
			if (sort != null) {
				switch (sort) {
				case AMOUNT:
					orderBy = " ORDER BY Amount DESC";
					break;
				case DATE:
					orderBy = " ORDER BY " + dateColumnName + " ASC";
					break;
				}
			}
			
			// FIX: the splitting of entity ids will cause the top/limit function to be incorrect and require some sort of fix post collection.
			
			String focusIds = "";
			if (linkFilter != null) {
				linkFilter = TypedId.nativeFromTypedIds(linkFilter);
				focusIds = DataAccessHelper.createNodeIdListFromCollection(linkFilter, true, false);
			}
			
			final String financials2 = getNamespaceHandler().tableName(null, "Financials2");
			
			List<String> idsCopy = new ArrayList<String>(ns_entities); // copy the ids as we will take 100 at a time to process and the take method is destructive
			while (idsCopy.size() > 0) {
				List<String> tempSubList = (idsCopy.size() > 100) ? tempSubList = idsCopy.subList(0, 99) : idsCopy; // get the next 100
				List<String> subIds = new ArrayList<String>(tempSubList); // copy as the next step is destructive
				tempSubList.clear(); // this clears the IDs from idsCopy as tempSubList is backed by idsCopy 
				
				String fromIds = buildSearchIdsString(getNamespaceHandler().escapeColumnName("From"), subIds);
				String toIds = buildSearchIdsString(getNamespaceHandler().escapeColumnName("To"), subIds);
				String fromFocus = buildSearchIdsString(getNamespaceHandler().escapeColumnName("From"), linkFilter);
				String toFocus = buildSearchIdsString(getNamespaceHandler().escapeColumnName("To"), linkFilter);
				
				String selector = " from " +financials2+ 
						" where " + dateColumnName + " between '"+DataAccessHelper.format(startDate)+"' and '"+DataAccessHelper.format(endDate)+
						"' and ((" + fromIds + (focusIds.isEmpty() ? "" : " and " + toFocus) +
						") or (" + toIds + (focusIds.isEmpty() ? "" : " and " + fromFocus) +
						")) ";
				
				final String sql = "select * from ("+
						getNamespaceHandler().rowLimit("*"+ selector + orderBy, max)
							+ ") a union all select NULL,NULL,NULL,NULL,NULL,COUNT(*)" + selector;
				
				s_logger.trace("execute: " + sql);

				if (stmt.execute(sql)) {
					ResultSet rs = stmt.getResultSet();
					while (rs.next()) {
						String from = rs.getString("From");
						
						if (from == null) {
							results.setTotal(rs.getLong("loan_id"));
							
							continue;
						}
						
						String to = rs.getString("To");
						Date date = new java.util.Date(rs.getTimestamp("Date").getTime());
						Double amount = rs.getDouble("Amount");
						String comment = rs.getString("Comment");
						
						Double credit = checkEntitiesContainsEntity(ns_entities, to) ? amount : 0.0;
						Double debit = checkEntitiesContainsEntity(ns_entities, from) ? amount : 0.0;

						// drop the postfix if we're sorting by amount so that linklist only has 1 value
						// could probably generalize this but let's be conservative for safety's sake
						if(FL_SortBy.AMOUNT == sort) {		
							if(from.indexOf('-') != -1) {
								from = from.substring(0, from.indexOf('-'));
							}
							if(to.indexOf('-') != -1) {
								to = to.substring(0, to.indexOf('-'));
							}
						}
						
						// do some aesthetic enhancements on the comments field; this can be removed if the source tables 
						// are cleaned or properly regenerated in the future
						comment = formatCommentString(comment);
						
						List<FL_Property> properties = new ArrayList<FL_Property>();
						properties.add(
							new PropertyHelper(
								"inflowing", 
								"inflowing", 
								credit, 
								Arrays.asList(
									FL_PropertyTag.INFLOWING, 
									FL_PropertyTag.AMOUNT, 
									FL_PropertyTag.USD
								)
							)
						);
						properties.add(
							new PropertyHelper(
								"outflowing", 
								"outflowing", 
								debit, 
								Arrays.asList(
									FL_PropertyTag.OUTFLOWING, 
									FL_PropertyTag.AMOUNT, 
									FL_PropertyTag.USD
								)
							)
						);
						properties.add(
							new PropertyHelper(
								"comment", 
								"comment", 
								comment, 
								Collections.singletonList(
									FL_PropertyTag.ANNOTATION
								)
							)
						);
						properties.add(
							new PropertyHelper(
								FL_PropertyTag.DATE, 
								date
							)
						);
						properties.add(
							new PropertyHelper(
								FL_PropertyTag.ID, 
								rs.getString("loan_id")
							)
						);
						
						FL_Link link = new FL_Link(Collections.singletonList(FL_LinkTag.FINANCIAL), 
								TypedId.fromNativeId(TypedId.ACCOUNT, from).getTypedId(), 
								TypedId.fromNativeId(TypedId.ACCOUNT, to).getTypedId(), 
								true, null, null, properties);
						links.add(link);
					}
					rs.close();
				}
				
				stmt.close();
				connection.close();
			}
		} catch (SQLException e) {
			throw new AvroRemoteException(e);
		} catch (ClassNotFoundException e) {
			throw new AvroRemoteException(e);
		}

		return results.build();
	}
	
	
	
	
	private boolean checkEntitiesContainsEntity(List<String> entities, String entity) {
		if (entities.contains(entity)) {
			return true;
		}
		
		// check for full partners against brokers
		if (entity.startsWith("p") && entity.contains("-")) {
			String fullPartner = entity.split("-")[0];
			if (entities.contains(fullPartner)) {
				return true;
			}
		}
		
		return false;
	}




	private String buildSearchIdsString(String column, List<String> ids) {
		
		if (ids == null) {
			return "1=1";
		}
		
		List<String> ins = new ArrayList<String>();
		List<String> likes = new ArrayList<String>();
		for (String id : ids) {
			if (id.startsWith("p")) {
				if (id.contains("-")) {
					ins.add(id);
				} else {
					likes.add(id + "-%");
				}
			} else {
				ins.add(id);
			}
		}
		
		Boolean needsOr = false;
		
		StringBuilder str = new StringBuilder();
		str.append("(");
		if (ins.size() > 0) {
			for (int i = 0; i < ins.size() - 1; i++) {
				str.append(column + " = '" + ins.get(i) + "'");
				str.append(" or ");
			}
			str.append(column + " = '" + ins.get(ins.size() - 1) + "'");
			needsOr = true;
		}

		if (likes.size() > 0) {
			
			if (needsOr) {
				str.append(" or ");
			}
			
			for (int i = 0; i < likes.size() - 1; i++) {
				str.append(column + " like '" + likes.get(i) + "'");
				str.append(" or ");
			}
			str.append(column + " like '" + likes.get(likes.size() - 1) + "'");
		}
		
		str.append(")");
		
		return str.toString();
	}
	
	
	
	
	// clean up bad comments - should have been done properly in the first place
	private static String formatCommentString(String comment) {
		
		if (comment == null) return "";
		
		if(comment.contains("e+") || comment.contains("000")) {
			int plusIdx = comment.indexOf('+');			// need to remove + character string so that stringRep won't implode
			if(plusIdx != -1) {
				comment = comment.substring(0, plusIdx) + comment.substring(plusIdx+1);
			}

			String[] fields = comment.split("\\s+");
			if(fields.length > 3 && fields[3] != null &&
					(fields[3].contains("e") || fields[3].contains("000"))) {								
				comment = comment.replaceFirst(fields[3],  monetaryFormat(fields[3]));
			}
		}
		
		
		int startIndex = comment.indexOf("(");
		int endIndex = comment.indexOf(")");
		
		if (startIndex>-1 && endIndex>-1) {
			String currencyComment = comment.substring(startIndex + 1, endIndex);
			
			String[] cur = currencyComment.split(" in ");
			
			if (cur.length > 1) {
			
				Double val = Double.parseDouble(cur[0]);		
				return comment.substring(0, startIndex) + "(" + world_df.format(val) + " in " + cur[1] + ")";
			}
			
			try {
				Double val = Double.parseDouble(cur[0]);
				return comment.substring(0, startIndex) + "(" + world_df.format(val) + ")";
			} catch (NumberFormatException e) {
				// do nothing
			}
		}
		
		return comment;
	}
	
	
	
	
	private static String[] MONETARY_SUFFIX = new String[]{"","k", "m", "b", "t"};
	
	
	
	
	private static final String monetaryFormat(final String toFormat) {
		String toReturn = toFormat;
		
		try {
			double dblValue = new BigDecimal(toFormat).doubleValue();
			toReturn = new DecimalFormat("##0E0").format(dblValue);
			toReturn = toReturn.replaceAll("E[0-9]*", MONETARY_SUFFIX[((int) Math.log10(dblValue))/3]);
		}
		catch(NumberFormatException nfe) { toReturn = toFormat; }
		catch(IllegalArgumentException iae) { toReturn = toFormat; }
		catch(ArithmeticException ae) { toReturn = toFormat; }
		
		return toReturn;
	}
}
