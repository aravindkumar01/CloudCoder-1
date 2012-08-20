// CloudCoder - a web-based pedagogical programming environment
// Copyright (C) 2011-2012, Jaime Spacco <jspacco@knox.edu>
// Copyright (C) 2011-2012, David H. Hovemeyer <david.hovemeyer@gmail.com>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.cloudcoder.app.server.persist;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.cloudcoder.app.shared.model.Change;
import org.cloudcoder.app.shared.model.ChangeType;
import org.cloudcoder.app.shared.model.ConfigurationSetting;
import org.cloudcoder.app.shared.model.ConfigurationSettingName;
import org.cloudcoder.app.shared.model.Course;
import org.cloudcoder.app.shared.model.CourseRegistration;
import org.cloudcoder.app.shared.model.CourseRegistrationType;
import org.cloudcoder.app.shared.model.Event;
import org.cloudcoder.app.shared.model.IContainsEvent;
import org.cloudcoder.app.shared.model.NetCoderAuthenticationException;
import org.cloudcoder.app.shared.model.Problem;
import org.cloudcoder.app.shared.model.ProblemAndSubmissionReceipt;
import org.cloudcoder.app.shared.model.ProblemAndTestCaseList;
import org.cloudcoder.app.shared.model.ProblemData;
import org.cloudcoder.app.shared.model.ProblemLicense;
import org.cloudcoder.app.shared.model.ProblemList;
import org.cloudcoder.app.shared.model.ProblemSummary;
import org.cloudcoder.app.shared.model.SubmissionReceipt;
import org.cloudcoder.app.shared.model.SubmissionStatus;
import org.cloudcoder.app.shared.model.Term;
import org.cloudcoder.app.shared.model.TestCase;
import org.cloudcoder.app.shared.model.TestResult;
import org.cloudcoder.app.shared.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of IDatabase using JDBC.
 * 
 * @author David Hovemeyer
 */
public class JDBCDatabase implements IDatabase {
	// Constants for table names
	public static final String TEST_RESULTS = "cc_test_results";
	public static final String TEST_CASES = "cc_test_cases";
	public static final String SUBMISSION_RECEIPTS = "cc_submission_receipts";
	public static final String TERMS = "cc_terms";
	public static final String EVENTS = "cc_events";
	public static final String CHANGES = "cc_changes";
	public static final String COURSE_REGISTRATIONS = "cc_course_registrations";
	public static final String COURSES = "cc_courses";
	public static final String PROBLEMS = "cc_problems";
	public static final String USERS = "cc_users";
	public static final String CONFIGURATION_SETTINGS = "cc_configuration_settings";

	private static final Logger logger=LoggerFactory.getLogger(JDBCDatabase.class);
	
	private String jdbcUrl;
	
	public JDBCDatabase() {
		JDBCDatabaseConfig config = JDBCDatabaseConfig.getInstance();
		jdbcUrl = "jdbc:mysql://" +
				config.getDbHost() + config.getDbPortStr() +
				"/" +
				config.getDbDatabaseName() +
				"?user=" +
				config.getDbUser() +
				"&password=" + config.getDbPasswd();
		logger.info("Database URL: "+jdbcUrl);
		//System.out.println("URL: " +jdbcUrl);
	}
	
	static {
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch (Exception e) {
			throw new IllegalStateException("Could not load mysql jdbc driver", e);
		}
	}

	private static class InUseConnection {
		Connection conn;
		int refCount;
	}
	
	/*
	 * Need to consider how to do connection management better.
	 * For now, just use something simple that works.
	 */

	private ThreadLocal<InUseConnection> threadLocalConnection = new ThreadLocal<InUseConnection>();
	
	private Connection getConnection() throws SQLException {
		InUseConnection c = threadLocalConnection.get();
		if (c == null) {
			c = new InUseConnection();
			c.conn = DriverManager.getConnection(jdbcUrl);
			c.refCount = 0;
			threadLocalConnection.set(c);
		}
		c.refCount++;
		return c.conn;
	}
	
	private void releaseConnection() throws SQLException {
		InUseConnection c = threadLocalConnection.get();
		c.refCount--;
		if (c.refCount == 0) {
			c.conn.close();
			threadLocalConnection.set(null);
		}
	}
	
	@Override
	public ConfigurationSetting getConfigurationSetting(final ConfigurationSettingName name) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<ConfigurationSetting>() {
			@Override
			public ConfigurationSetting run(Connection conn)
					throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select s.* from " + CONFIGURATION_SETTINGS + " as s where s.name = ?");
				stmt.setString(1, name.toString());
				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				ConfigurationSetting configurationSetting = new ConfigurationSetting();
				load(configurationSetting, resultSet, 1);
				return configurationSetting;
			}
			@Override
			public String getDescription() {
				return "retrieving configuration setting";
			}
		});
	}
	
	private User getUser(Connection conn, String userName) throws SQLException {
	    PreparedStatement stmt = conn.prepareStatement("select * from "+USERS+" where username = ?");
        stmt.setString(1, userName);
        
        ResultSet resultSet = stmt.executeQuery();
        if (!resultSet.next()) {
            return null;
        }
        
        User user = new User();
        load(user, resultSet, 1);
        return user;
	}
	
	@Override
	public User authenticateUser(final String userName, final String password) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<User>() {
			@Override
			public User run(Connection conn) throws SQLException {
				User user=getUser(conn, userName);
				
				if (BCrypt.checkpw(password, user.getPasswordHash())) {
					// Plaintext password matches hash: authentication succeeded
					return user;
				} else {
					// Plaintext password does not match hash: authentication failed
					return null;
				}
			}
			@Override
			public String getDescription() {
				return "retrieving user";
			}
		});
	};
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#getUserWithoutAuthentication(java.lang.String)
	 */
	@Override
	public User getUserWithoutAuthentication(final String userName) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<User>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public User run(Connection conn) throws SQLException {
				return getUser(conn, userName);
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "retrieving user for username";
			}
		});
	}
	
	@Override
	public Problem getProblem(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<Problem>() {
			@Override
			public Problem run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select p.* from " + PROBLEMS + " as p, " + COURSES + " as c, " + COURSE_REGISTRATIONS + " as r " +
						" where p.problem_id = ? " +
						"   and c.id = p.course_id " +
						"   and r.course_id = c.id " +
						"   and r.user_id = ?" +
						//  An instructor can see any problem in a course.
						//  A student can only see a problem if it is visible.
						"   and (r.registration_type >= " + CourseRegistrationType.INSTRUCTOR.ordinal() + " or p.visible <> 0)"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				
				if (!resultSet.next()) {
					// no such problem, or user is not authorized to see this problem
					return null;
				}
				
				Problem problem = new Problem();
				load(problem, resultSet, 1);
				return problem;
			}
			
			@Override
			public String getDescription() {
				return "retrieving problem";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#getProblem(int)
	 */
	@Override
	public Problem getProblem(final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<Problem>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Problem run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select * from " + PROBLEMS + " where problem_id = ?");
				stmt.setInt(1, problemId);
				
				ResultSet resultSet = executeQuery(stmt);
				if (resultSet.next()) {
					Problem problem = new Problem();
					load(problem, resultSet, 1);
					return problem;
				}
				return null;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "get problem";
			}
		});
	}
	
	@Override
	public Change getMostRecentChange(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<Change>() {
			@Override
			public Change run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from " + CHANGES + " as c, " + EVENTS + " as e " +
						" where c.event_id = e.id " +
						"   and e.id = (select max(ee.id) from " + CHANGES + " as cc, " + EVENTS + " as ee " +
						"                where cc.event_id = ee.id " +
						"                  and ee.problem_id = ? " +
						"                  and ee.user_id = ?)"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				
				Change change = new Change();
				load(change, resultSet, 1);
				return change;
			}
			public String getDescription() {
				return "retrieving latest code change";
			}
		});
	}
	
	@Override
	public Change getMostRecentFullTextChange(final User user, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<Change>() {
			@Override
			public Change run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from " + CHANGES + " as c, " + EVENTS + " as e " +
						" where c.event_id = e.id " +
						"   and e.id = (select max(ee.id) from " + CHANGES + " as cc, " + EVENTS + " as ee " +
						"                where cc.event_id = ee.id " +
						"                  and ee.problem_id = ? " +
						"                  and ee.user_id = ? " +
						"                  and cc.type = ?)"
				);
				stmt.setInt(1, problemId);
				stmt.setInt(2, user.getId());
				stmt.setInt(3, ChangeType.FULL_TEXT.ordinal());

				ResultSet resultSet = executeQuery(stmt);
				if (!resultSet.next()) {
					return null;
				}
				Change change = new Change();
				load(change, resultSet, 1);
				return change;
			}
			@Override
			public String getDescription() {
				return " retrieving most recent full text change";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#getFullTextChange(long)
	 */
	@Override
	public Change getChange(final int changeEventId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<Change>(){
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Change run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select ch.*, e.* " +
						"  from " + CHANGES + " as ch, " + EVENTS + " as e " +
						" where e.id = ? and ch.event_id = e.id");
				stmt.setInt(1, changeEventId);
				
				ResultSet resultSet = executeQuery(stmt);
				if (resultSet.next()) {
					Change change = getChangeAndEvent(resultSet);
					return change;
				}
				
				return null;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "get text change";
			}
		});
	}
	
	@Override
	public List<Change> getAllChangesNewerThan(final User user, final int problemId, final int baseRev) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<List<Change>>() {
			@Override
			public List<Change> run(Connection conn) throws SQLException {
				List<Change> result = new ArrayList<Change>();
				
				PreparedStatement stmt = prepareStatement(
						conn,
						"select c.* from " + CHANGES + " as c, " + EVENTS + " as e " +
						" where c.event_id = e.id " +
						"   and e.id > ? " +
						"   and e.user_id = ? " +
						"   and e.problem_id = ? " +
						" order by e.id asc"
				);
				stmt.setInt(1, baseRev);
				stmt.setInt(2, user.getId());
				stmt.setInt(3, problemId);
				
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					Change change = new Change();
					load(change, resultSet, 1);
					result.add(change);
				}
				
				return result;
			}
			@Override
			public String getDescription() {
				return " retrieving most recent text changes";
			}
		});
	}
	
	@Override
	public List<? extends Object[]> getCoursesForUser(final User user) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<List<? extends Object[]>>() {
			@Override
			public List<? extends Object[]> run(Connection conn) throws SQLException {
				return doGetCoursesForUser(user, conn, this);
			}
			@Override
			public String getDescription() {
				return " retrieving courses for user";
			}
		});
	}

	@Override
	public ProblemList getProblemsInCourse(final User user, final Course course) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<ProblemList>() {
			@Override
			public ProblemList run(Connection conn) throws SQLException {
				return new ProblemList(doGetProblemsInCourse(user, course, conn, this));
			}
			@Override
			public String getDescription() {
				return "retrieving problems for course";
			}
		});
	}

	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#getProblemAndSubscriptionReceiptsInCourse(org.cloudcoder.app.shared.model.User, org.cloudcoder.app.shared.model.Course)
	 */
	@Override
	public List<ProblemAndSubmissionReceipt> getProblemAndSubscriptionReceiptsInCourse(
			final User user, final Course course) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<List<ProblemAndSubmissionReceipt>>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public List<ProblemAndSubmissionReceipt> run(Connection conn)
					throws SQLException {
				// Get all problems for this user/course
				List<Problem> problemList = doGetProblemsInCourse(user, course, conn, this);
				
				// Get all submission receipts for this user/course.
				// Note that we join on course_registrations in order to ensure 
				// that user is authorized to get information about the course.
				// We also check for each problem that the user is either an instructor
				// or that the problem is visible, since we don't want to show
				// information for problems that the student shouldn't have
				// access to.
				PreparedStatement stmt = prepareStatement(
						conn,
						"select r.*, e.* from " + SUBMISSION_RECEIPTS + " as r, " + PROBLEMS + " as p, " + EVENTS + " as e, " + COURSE_REGISTRATIONS + " as cr " +
						" where cr.user_id = ?" +
						"   and cr.course_id = ? " +
						"   and (cr.registration_type >= " + CourseRegistrationType.INSTRUCTOR.ordinal() + " or p.visible <> 0)" +
						"   and p.course_id = cr.course_id " +
						"   and e.problem_id = p.problem_id " +
						"   and e.user_id = cr.user_id " +
						"   and r.event_id = e.id "
				);
				stmt.setInt(1, user.getId());
				stmt.setInt(2, course.getId());
				
				// Map of problem ids to most recent submission receipt for problem
				Map<Integer, SubmissionReceipt> problemIdToMostRecentSubmissionReceiptMap = new HashMap<Integer, SubmissionReceipt>();
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					SubmissionReceipt submissionReceipt = loadSubmissionReceiptAndEvent(resultSet);
					SubmissionReceipt current = problemIdToMostRecentSubmissionReceiptMap.get(submissionReceipt.getEvent().getProblemId());
					if (current == null || submissionReceipt.getEventId() > current.getEventId()) {
						problemIdToMostRecentSubmissionReceiptMap.put(submissionReceipt.getEvent().getProblemId(), submissionReceipt);
					}
				}
				
				// Match up problems and corresponding submission receipts.
				List<ProblemAndSubmissionReceipt> result = new ArrayList<ProblemAndSubmissionReceipt>();
				for (Problem problem : problemList) {
					SubmissionReceipt receipt = problemIdToMostRecentSubmissionReceiptMap.get(problem.getProblemId());
					ProblemAndSubmissionReceipt problemAndSubscriptionReceipt = new ProblemAndSubmissionReceipt(problem, receipt);
					result.add(problemAndSubscriptionReceipt);
				}
				
				return result;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "retrieving problems and subscription receipts for course";
			}
		});
	}
	
	@Override
	public void storeChanges(final Change[] changeList) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			@Override
			public Boolean run(Connection conn) throws SQLException {
				// Store Events
				storeEvents(changeList, conn, this);
				
				// Store Changes
				PreparedStatement insertChange = prepareStatement(
						conn,
						"insert into " + CHANGES + " values (?, ?, ?, ?, ?, ?, ?, ?)"
				);
				for (Change change : changeList) {
					store(change, insertChange, 1);
					insertChange.addBatch();
				}
				insertChange.executeBatch();
				
				return true;
			}
			@Override
			public String getDescription() {
				return "storing text changes";
			}
		});
	}
	
	@Override
	public List<TestCase> getTestCasesForProblem(final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<List<TestCase>>() {
			@Override
			public List<TestCase> run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select * from " + TEST_CASES + " where problem_id = ?");
				stmt.setInt(1, problemId);
				
				List<TestCase> result = new ArrayList<TestCase>();
				
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					TestCase testCase = new TestCase();
					load(testCase, resultSet, 1);
					result.add(testCase);
				}
				return result;
			}
			@Override
			public String getDescription() {
				return "getting test cases for problem";
			}
		});
	}
	
	@Override
	public TestCase[] getTestCasesForProblem(final User authenticatedUser, final int problemId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<TestCase[]>() {
			@Override
			public TestCase[] run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select tc.* " +
						"   from " + TEST_CASES + " as tc, " + PROBLEMS + " as p, " + COURSE_REGISTRATIONS + " as cr " +
						"  where tc.problem_id = p.problem_id " +
						"    and p.problem_id = ? " +
						"    and p.course_id =  cr.course_id " +
						"    and cr.user_id = ? " +
						"    and cr.registration_type >= ? " +
						"order by tc.test_case_id asc"
						);
				stmt.setInt(1, problemId);
				stmt.setInt(2, authenticatedUser.getId());
				stmt.setInt(3, CourseRegistrationType.INSTRUCTOR.ordinal());
				
				ResultSet resultSet = executeQuery(stmt);
				List<TestCase> result = new ArrayList<TestCase>();
				while (resultSet.next()) {
					TestCase testCase = new TestCase();
					load(testCase, resultSet, 1);
					result.add(testCase);
				}
				
				if (result.isEmpty()) {
					// Most likely, the user is not authorized (not an instructor for the course)
					return null;
				}
				
				// Success!
				return result.toArray(new TestCase[result.size()]);
			}
			@Override
			public String getDescription() {
				return "getting test cases for problem";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#insertSubmissionReceipt(org.cloudcoder.app.shared.model.SubmissionReceipt)
	 */
	@Override
	public void insertSubmissionReceipt(final SubmissionReceipt receipt, final TestResult[] testResultList_) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Boolean run(Connection conn) throws SQLException {
				doInsertSubmissionReceipt(receipt, testResultList_, conn, this);
				return true;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "storing submission receipt";
			}
		});
	}
	
	public void insertUsersFromInputStream(final InputStream in, final Course course) {
	    try {
	    databaseRunAuth(new AbstractDatabaseRunnable<Boolean>() {

            @Override
            public Boolean run(Connection conn) throws SQLException,NetCoderAuthenticationException
            {
                doInsertUsersFromInputStream(in, course, conn);
                return true;
            }

            @Override
            public String getDescription() {
                return "Inserting users into course "+course.getName();
            }
	        
        });
	    } catch (NetCoderAuthenticationException e) {
	        // TODO proper error handling
	        throw new RuntimeException(e);
	    }
	}
	
	private void doInsertUsersFromInputStream(InputStream in, Course course, Connection conn)
	throws SQLException
	{
	    conn.setAutoCommit(false);
	    // Assuming that the users are in the following format:
	    // firstname   lastname    username    password    email
        //TODO: CreateWebApp should prompt for firstname/lastname/email as well
	    //TODO: Add first/last/email to the User record
	    Scanner scan=new Scanner(in);
	    PreparedStatement stmt=conn.prepareStatement("insert into " +USERS+
                " (username, password_hash) values (?, ?)");
	    
	    while (scan.hasNextLine()) {
	        String line=scan.nextLine();
	        String[] tokens=line.split("\t");
	        String username=tokens[2];
	        String password=tokens[3];
	        stmt.setString(1, username);
	        stmt.setString(2, password);
	        stmt.addBatch();
	    }
	    stmt.execute();
	    conn.commit();
    }

	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#addSubmissionReceiptIfNecessary(org.cloudcoder.app.shared.model.User, org.cloudcoder.app.shared.model.Problem)
	 */
	@Override
	public void getOrAddLatestSubmissionReceipt(final User user, final Problem problem) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<SubmissionReceipt>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public SubmissionReceipt run(Connection conn) throws SQLException {
				// Get most recent submission receipt for user/problem
				PreparedStatement stmt = prepareStatement(
						conn,
						"select r.*, e.* from " + SUBMISSION_RECEIPTS + " as r, " + EVENTS + " as e " +
						" where r.event_id = e.id " +
						"   and e.id = (select max(ee.id) from " + SUBMISSION_RECEIPTS + " as rr, " + EVENTS + " as ee " +
						"                where rr.event_id = ee.id " +
						"                  and ee.problem_id = ? " +
						"                  and ee.user_id = ?)");
				stmt.setInt(1, problem.getProblemId());
				stmt.setInt(2, user.getId());
				
				ResultSet resultSet = executeQuery(stmt);
				if (resultSet.next()) {
					SubmissionReceipt submissionReceipt = loadSubmissionReceiptAndEvent(resultSet);
					return submissionReceipt;
				}
				
				// There is no submission receipt in the database yet, so add one
				// with status STARTED
				SubmissionStatus status = SubmissionStatus.STARTED;
				SubmissionReceipt receipt = SubmissionReceipt.create(user, problem, status, -1, 0, 0);
				doInsertSubmissionReceipt(receipt, new TestResult[0], conn, this);
				return receipt;
				
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "adding initial submission receipt if necessary";
			}
		});
	}
	
	@Override
	public void addProblem(final Problem problem) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Boolean run(Connection conn) throws SQLException {
				return doInsertProblem(problem, conn, (AbstractDatabaseRunnableNoAuthException<?>) this);
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "adding Problem";
			}
		});
	}
	
	@Override
	public void addTestCases(final Problem problem, final List<TestCase> testCaseList) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Boolean run(Connection conn) throws SQLException {
				AbstractDatabaseRunnableNoAuthException<?> databaseRunnable = this;
				
				return doInsertTestCases(problem, testCaseList, conn,
						databaseRunnable);
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "adding test case";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#createProblemSummary(org.cloudcoder.app.shared.model.Problem)
	 */
	@Override
	public ProblemSummary createProblemSummary(final Problem problem) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<ProblemSummary>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public ProblemSummary run(Connection conn) throws SQLException {
				// Determine how many students (non-instructor users) are in this course
				int numStudentsInCourse = doCountStudentsInCourse(problem, conn, this);
				
				// Get all SubmissionReceipts
				PreparedStatement stmt = prepareStatement(
						conn,
						"select sr.*, e.* " +
						"  from " + SUBMISSION_RECEIPTS + " as sr, " + EVENTS + " as e " +
						" where sr.event_id = e.id " +
						"   and e.problem_id = ?");
				stmt.setInt(1, problem.getProblemId());
				
				// Keep track of "best" submissions from each student.
				HashMap<Integer, SubmissionReceipt> bestSubmissions = new HashMap<Integer, SubmissionReceipt>();
				
				ResultSet resultSet = executeQuery(stmt);
				while (resultSet.next()) {
					SubmissionReceipt receipt = loadSubmissionReceiptAndEvent(resultSet);
					
					SubmissionReceipt prevBest = bestSubmissions.get(receipt.getEvent().getUserId());
					SubmissionStatus curStatus = receipt.getStatus();
					//SubmissionStatus prevStatus = prevBest.getStatus();
					if (prevBest == null
							|| curStatus == SubmissionStatus.TESTS_PASSED && prevBest.getStatus() != SubmissionStatus.TESTS_PASSED
							|| receipt.getNumTestsPassed() > prevBest.getNumTestsPassed()) {
						// New receipt is better than the previous receipt
						bestSubmissions.put(receipt.getEvent().getUserId(), receipt);
					}
				}
				
				// Aggregate the data
				int started = 0;
				int anyPassed = 0;
				int allPassed = 0;
				for (SubmissionReceipt r : bestSubmissions.values()) {
					if (r.getStatus() == SubmissionStatus.TESTS_PASSED) {
						started++;
						allPassed++;
						anyPassed++;
					} else if (r.getNumTestsPassed() > 0) {
						started++;
						anyPassed++;
					} else {
						started++;
					}
				}
				
				// Create the ProblemSummary
				ProblemSummary problemSummary = new ProblemSummary();
				problemSummary.setProblem(problem);
				problemSummary.setNumStudents(numStudentsInCourse);
				problemSummary.setNumStarted(started);
				problemSummary.setNumPassedAtLeastOneTest(anyPassed);
				problemSummary.setNumCompleted(allPassed);
				
				return problemSummary;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "get problem summary for problem";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#getSubmissionReceipt(int)
	 */
	@Override
	public SubmissionReceipt getSubmissionReceipt(final int submissionReceiptId) {
		return databaseRun(new AbstractDatabaseRunnableNoAuthException<SubmissionReceipt>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public SubmissionReceipt run(Connection conn) throws SQLException {
				PreparedStatement stmt = prepareStatement(
						conn,
						"select sr.*, e.* " +
						"  from " + SUBMISSION_RECEIPTS + " as sr, " +
						"       " + EVENTS + " as e " +
						" where sr.event_id = e.id " +
						"   and e.event_id = ?");
				stmt.setInt(1, submissionReceiptId);
				
				ResultSet resultSet = executeQuery(stmt);
				
				if (resultSet.next()) {
					SubmissionReceipt receipt = loadSubmissionReceiptAndEvent(resultSet);
					return receipt;
				}
				return null;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "getting submission receipt";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#insertTestResults(org.cloudcoder.app.shared.model.TestResult[], int)
	 */
	@Override
	public void replaceTestResults(final TestResult[] testResults, final int submissionReceiptId) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Boolean run(Connection conn) throws SQLException {
				// Delete old test results (if any)
				PreparedStatement delTestResults = prepareStatement(
						conn,
						"delete from " + TEST_RESULTS + " where submission_receipt_event_id = ?");
				delTestResults.setInt(1, submissionReceiptId);
				delTestResults.executeUpdate();
				
				// Insert new test results
				doInsertTestResults(testResults, submissionReceiptId, conn, this);
				
				return true;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "store test results";
			}
		});
	}
	
	/* (non-Javadoc)
	 * @see org.cloudcoder.app.server.persist.IDatabase#updateSubmissionReceipt(org.cloudcoder.app.shared.model.SubmissionReceipt)
	 */
	@Override
	public void updateSubmissionReceipt(final SubmissionReceipt receipt) {
		databaseRun(new AbstractDatabaseRunnableNoAuthException<Boolean>() {
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#run(java.sql.Connection)
			 */
			@Override
			public Boolean run(Connection conn) throws SQLException {
				// Only update the fields that we expect might have changed
				// following a retest.
				PreparedStatement stmt = prepareStatement(
						conn,
						"update " + SUBMISSION_RECEIPTS + 
						"  set status = ?, num_tests_attempted = ?, num_tests_passed = ?");
				stmt.setInt(1, receipt.getStatus().ordinal());
				stmt.setInt(2, receipt.getNumTestsAttempted());
				stmt.setInt(3, receipt.getNumTestsPassed());
				
				stmt.executeUpdate();
				
				return true;
			}
			/* (non-Javadoc)
			 * @see org.cloudcoder.app.server.persist.DatabaseRunnable#getDescription()
			 */
			@Override
			public String getDescription() {
				return "update submission receipt";
			}
		});
	}
	
	@Override
	public ProblemAndTestCaseList storeProblemAndTestCaseList(
			final ProblemAndTestCaseList problemAndTestCaseList, final Course course, final User user)
			throws NetCoderAuthenticationException {
		return databaseRunAuth(new AbstractDatabaseRunnable<ProblemAndTestCaseList>() {
			@Override
			public ProblemAndTestCaseList run(Connection conn)
					throws SQLException, NetCoderAuthenticationException {
				// Ensure problem and course id match.
				if (!problemAndTestCaseList.getProblem().getCourseId().equals((Integer) course.getId())) {
					throw new NetCoderAuthenticationException("Problem does not match course");
				}
				
				// Check that user is registered as an instructor in the course.
				boolean isInstructor = false;
				List<? extends Object[]> courses = doGetCoursesForUser(user, conn, this);
				for (Object[] tuple : courses) {
					CourseRegistration courseReg = (CourseRegistration) tuple[2];
					if (courseReg.getCourseId() == course.getId()
							&& courseReg.getRegistrationType().ordinal() >= CourseRegistrationType.INSTRUCTOR.ordinal()) {
							isInstructor = true;
						break;
					}
				}
				if (!isInstructor) {
					throw new NetCoderAuthenticationException("not instructor in course");
				}
				
				// If the problem id is not set, then insert the problem.
				// Otherwise, update the existing problem.
				if (problemAndTestCaseList.getProblem().getProblemId() == null) {
					// Insert problem and test cases
					doInsertProblem(problemAndTestCaseList.getProblem(), conn, this);
					doInsertTestCases(
							problemAndTestCaseList.getProblem(),
							Arrays.asList(problemAndTestCaseList.getTestCaseList()),
							conn,
							this);
				} else {
					// Update problem and test cases
					doUpdateProblem(problemAndTestCaseList.getProblem(), conn, this);
					
					// We can achieve the effect of updating the test cases by deleting
					doDeleteTestCases(problemAndTestCaseList.getProblem().getProblemId(), conn, this);
					doInsertTestCases(
							problemAndTestCaseList.getProblem(),
							Arrays.asList(problemAndTestCaseList.getTestCaseList()),
							conn,
							this);
				}
				
				// Success!
				return problemAndTestCaseList;
			}

			@Override
			public String getDescription() {
				return " storing problem and test cases";
			}
		});
	}

	/**
	 * Run a database transaction and return the result.
	 * This method is for transactions that extend {@link AbstractDatabaseRunnableNoAuthException}
	 * and thus are guaranteed not to throw {@link NetCoderAuthenticationException}.
	 * 
	 * @param databaseRunnable the transaction to run
	 * @return the result
	 */
	private<E> E databaseRun(AbstractDatabaseRunnableNoAuthException<E> databaseRunnable) {
		try {
			return doDatabaseRun(databaseRunnable);
		} catch (NetCoderAuthenticationException e) {
			// The fact that the method takes an
			// AbstractDatabaseRunnableNoAuthException guarantees that the transaction
			// won't throw NetcoderAuthenticationException.
			throw new IllegalStateException("Can't happen", e);
		}
	}

	/**
	 * Run a database transaction and return the result.
	 * This method is for transactions that check the authenticity of provided
	 * user credentials and may throw {@link NetCoderAuthenticationException}.
	 * 
	 * @param databaseRunnable the transaction to run
	 * @return the result
	 */
	private<E> E databaseRunAuth(AbstractDatabaseRunnable<E> databaseRunnable) throws NetCoderAuthenticationException {
		return doDatabaseRun(databaseRunnable);
	}

	private<E> E doDatabaseRun(DatabaseRunnable<E> databaseRunnable) throws NetCoderAuthenticationException {
		try {
			Connection conn = null;
			boolean committed = false;
			try {
				conn = getConnection();
				conn.setAutoCommit(false);
				// FIXME: should retry if deadlock is detected
				E result = databaseRunnable.run(conn);
				conn.commit();
				committed = true;
				return result;
			} finally {
				if (conn != null) {
					if (!committed) {
						conn.rollback();
					}
					databaseRunnable.cleanup();
					conn.setAutoCommit(true);
					releaseConnection();
				}
			}
		} catch (SQLException e) {
			throw new PersistenceException("SQLException", e);
		}
	}

	/**
	 * Store the Event objects embedded in the given IContainsEvent objects.
	 * 
	 * @param containsEventList list of IContainsEvent objects
	 * @param conn              database connection
	 * @param dbRunnable        an AbstractDatabaseRunnable that is managing statements and result sets
	 * @throws SQLException
	 */
	private void storeEvents(final IContainsEvent[] containsEventList, Connection conn, AbstractDatabaseRunnableNoAuthException<?> dbRunnable)
			throws SQLException {
		PreparedStatement insertEvent = dbRunnable.prepareStatement(
				conn,
				"insert into " + EVENTS + " values (NULL, ?, ?, ?, ?)", 
				Statement.RETURN_GENERATED_KEYS
		);
		for (IContainsEvent change : containsEventList) {
			storeNoId(change.getEvent(), insertEvent, 1);
			insertEvent.addBatch();
		}
		insertEvent.executeBatch();
		
		// Get the generated ids of the newly inserted Events
		ResultSet genKeys = dbRunnable.getGeneratedKeys(insertEvent);
		int count = 0;
		while (genKeys.next()) {
			int id = genKeys.getInt(1);
			containsEventList[count].getEvent().setId(id);
			containsEventList[count].setEventId(id);
			count++;
		}
		if (count != containsEventList.length) {
			throw new SQLException("Did not get all generated keys for inserted events");
		}
	}

	private void doInsertSubmissionReceipt(
			final SubmissionReceipt receipt,
			final TestResult[] testResultList_,
			Connection conn,
			AbstractDatabaseRunnableNoAuthException<?> dbRunnable)
			throws SQLException {
		// Get TestResults (ensuring that the array is non-null
		TestResult[] testResultList = testResultList_ != null ? testResultList_ : new TestResult[0];
		
		// Store the underlying Event
		storeEvents(new SubmissionReceipt[]{receipt}, conn, dbRunnable);
		
		// Set the SubmissionReceipt's event id to match the event we just inserted
		receipt.setEventId(receipt.getEvent().getId());
		
		// Insert the SubmissionReceipt
		PreparedStatement stmt = dbRunnable.prepareStatement(
				conn,
				"insert into " + SUBMISSION_RECEIPTS + " values (?, ?, ?, ?, ?)",
				PreparedStatement.RETURN_GENERATED_KEYS
		);
		store(receipt, stmt, 1);
		stmt.execute();
		
//		// Get the generated key for this submission receipt
//		ResultSet genKey = dbRunnable.getGeneratedKeys(stmt);
//		if (!genKey.next()) {
//			throw new SQLException("Could not get generated key for submission receipt");
//		}
//		receipt.setId(genKey.getInt(1));
		
		// Store the TestResults
//		doInsertTestResults(testResultList, receipt.getId(), conn, dbRunnable);
		doInsertTestResults(testResultList, receipt.getEventId(), conn, dbRunnable);
	}

	private void doInsertTestResults(TestResult[] testResultList,
			int submissionReceiptId, Connection conn,
			AbstractDatabaseRunnableNoAuthException<?> dbRunnable) throws SQLException {
		for (TestResult testResult : testResultList) {
			testResult.setSubmissionReceiptEventId(submissionReceiptId);
		}
		PreparedStatement insertTestResults = dbRunnable.prepareStatement(
				conn,
				"insert into " + TEST_RESULTS + " values (NULL, ?, ?, ?, ?, ?)",
				PreparedStatement.RETURN_GENERATED_KEYS
		);
		for (TestResult testResult : testResultList) {
			storeNoId(testResult, insertTestResults, 1);
			insertTestResults.addBatch();
		}
		insertTestResults.executeBatch();
		
		// Get generated TestResult ids
		ResultSet generatedIds = dbRunnable.getGeneratedKeys(insertTestResults);
		int count = 0;
		while (generatedIds.next()) {
			testResultList[count].setId(generatedIds.getInt(1));
			count++;
		}
		if (count != testResultList.length) {
			throw new SQLException("Wrong number of generated ids for test results");
		}
	}
	
	/**
	 * Get all problems for user/course.
	 * 
	 * @param user    a User
	 * @param course  a Course
	 * @param conn    the Connection
	 * @param dbRunnable the AbstractDatabaseRunnable
	 * @return List of Problems for user/course
	 * @throws SQLException 
	 */
	protected List<Problem> doGetProblemsInCourse(User user, Course course,
			Connection conn,
			AbstractDatabaseRunnableNoAuthException<?> dbRunnable) throws SQLException {
		
		//
		// Note that we have to join on course registrations to ensure
		// that we return courses that the user is actually registered for.
		// For each problem, we also have to check that the user is either
		// an instructor or that the problem is visible.  Students should
		// not be allowed to see problems that are not visible.
		//
		PreparedStatement stmt = dbRunnable.prepareStatement(
				conn,
				"select p.* from " + PROBLEMS + " as p, " + COURSES + " as c, " + COURSE_REGISTRATIONS + " as r " +
				" where p.course_id = c.id " +
				"   and r.course_id = c.id " +
				"   and r.user_id = ? " +
				"   and (r.registration_type >= " + CourseRegistrationType.INSTRUCTOR.ordinal() + " or p.visible <> 0)" +
				"   and c.id = ?"
		);
		stmt.setInt(1, user.getId());
		stmt.setInt(2, course.getId());
		
		ResultSet resultSet = dbRunnable.executeQuery(stmt);
		
		List<Problem> resultList = new ArrayList<Problem>();
		while (resultSet.next()) {
			Problem problem = new Problem();
			load(problem, resultSet, 1);
			resultList.add(problem);
		}
		
		return resultList;
	}

	/**
	 * @param problem
	 * @param conn
	 * @param abstractDatabaseRunnable
	 * @return
	 */
	protected int doCountStudentsInCourse(Problem problem, Connection conn,
			AbstractDatabaseRunnableNoAuthException<ProblemSummary> abstractDatabaseRunnable) {
		// TODO Auto-generated method stub
		return 0;
	}

	private List<? extends Object[]> doGetCoursesForUser(
			final User user,
			Connection conn,
			AbstractDatabaseRunnable<?> databaseRunnable) throws SQLException {
		List<Object[]> result = new ArrayList<Object[]>();

		PreparedStatement stmt = databaseRunnable.prepareStatement(
				conn,
				"select c.*, t.*, r.* from " + COURSES + " as c, " + TERMS + " as t, " + COURSE_REGISTRATIONS + " as r " +
				" where c.id = r.course_id " + 
				"   and c.term_id = t.id " +
				"   and r.user_id = ? " +
				" order by c.year desc, t.seq desc"
		);
		stmt.setInt(1, user.getId());
		
		ResultSet resultSet = databaseRunnable.executeQuery(stmt);
		
		while (resultSet.next()) {
			Course course = new Course();
			load(course, resultSet, 1);
			Term term = new Term();
			load(term, resultSet, Course.NUM_FIELDS + 1);
			CourseRegistration reg = new CourseRegistration();
			load(reg, resultSet, Course.NUM_FIELDS + Term.NUM_FIELDS + 1);
			result.add(new Object[]{course, term, reg});
		}
		
		return result;
	}

	private Boolean doInsertProblem(
			final Problem problem,
			Connection conn,
			AbstractDatabaseRunnable<?> databaseRunnable) throws SQLException {
		PreparedStatement stmt = databaseRunnable.prepareStatement(
				conn,
				"insert into " + PROBLEMS +
				" values (" +
				DBUtil.getInsertPlaceholdersNoId(Problem.SCHEMA) +
				")",
				PreparedStatement.RETURN_GENERATED_KEYS
		);
		
		storeNoId(problem, stmt, 1);
		
		stmt.executeUpdate();
		
		ResultSet generatedKey = databaseRunnable.getGeneratedKeys(stmt);
		if (!generatedKey.next()) {
			throw new SQLException("Could not get generated key for inserted problem");
		}
		problem.setProblemId(generatedKey.getInt(1));
		
		return true;
	}

	private Boolean doInsertTestCases(final Problem problem,
			final List<TestCase> testCaseList, Connection conn,
			AbstractDatabaseRunnable<?> databaseRunnable) throws SQLException {
		PreparedStatement stmt = databaseRunnable.prepareStatement(
				conn,
				"insert into " + TEST_CASES + " values (NULL, ?, ?, ?, ?, ?)",
				PreparedStatement.RETURN_GENERATED_KEYS
		);
		
		for (TestCase testCase : testCaseList) {
			testCase.setProblemId(problem.getProblemId());
			storeNoId(testCase, stmt, 1);
			stmt.addBatch();
		}
		
		stmt.executeBatch();
		
		ResultSet generatedKeys = databaseRunnable.getGeneratedKeys(stmt);
		int count = 0;
		while (generatedKeys.next()) {
			testCaseList.get(count).setTestCaseId(generatedKeys.getInt(1));
			count++;
		}
		if (count != testCaseList.size()) {
			throw new SQLException("wrong number of generated keys for inserted test cases");
		}
		
		return true;
	}

	private Boolean doUpdateProblem(
			final Problem problem,
			Connection conn,
			AbstractDatabaseRunnable<?> databaseRunnable) throws SQLException {
		
		PreparedStatement update = databaseRunnable.prepareStatement(
				conn,
				"update " + PROBLEMS +
				" set " + DBUtil.getUpdatePlaceholdersNoId(Problem.SCHEMA) +
				" where problem_id = ?"
				);
		int index = storeNoId(problem, update, 1);
		update.setInt(index, problem.getProblemId());
		
		int rowCount = update.executeUpdate();
		if (rowCount != 1) {
			throw new SQLException("Could not update problem (no such problem in database?)");
		}
		
		return true;
	}
	
	protected void doDeleteTestCases(
			Integer problemId,
			Connection conn,
			AbstractDatabaseRunnable<ProblemAndTestCaseList> abstractDatabaseRunnable) throws SQLException {
		PreparedStatement deleteStmt = abstractDatabaseRunnable.prepareStatement(
				conn,
				"delete from " + TEST_CASES + " where problem_id = ?");
		deleteStmt.setInt(1, problemId);
		
		deleteStmt.executeUpdate();
	}
	
	private void load(ConfigurationSetting configurationSetting, ResultSet resultSet, int index) throws SQLException {
		configurationSetting.setName(resultSet.getString(index++));
		configurationSetting.setValue(resultSet.getString(index++));
	}

	private void load(User user, ResultSet resultSet, int index) throws SQLException {
		user.setId(resultSet.getInt(index++));
		user.setUsername(resultSet.getString(index++));
		user.setPasswordHash(resultSet.getString(index++));
	}

	protected void load(Problem problem, ResultSet resultSet, int index) throws SQLException {
		problem.setProblemId(resultSet.getInt(index++));
		problem.setCourseId(resultSet.getInt(index++));
		problem.setWhenAssigned(resultSet.getLong(index++));
		problem.setWhenDue(resultSet.getLong(index++));
		problem.setVisible(resultSet.getBoolean(index++));
		loadProblemData(problem, resultSet, index);
	}

	protected void loadProblemData(ProblemData problemData, ResultSet resultSet, int index) throws SQLException {
		problemData.setProblemType(resultSet.getInt(index++));
		problemData.setTestname(resultSet.getString(index++));
		problemData.setBriefDescription(resultSet.getString(index++));
		problemData.setDescription(resultSet.getString(index++));
		problemData.setSkeleton(resultSet.getString(index++));
		problemData.setSchemaVersion(resultSet.getInt(index++));
		problemData.setAuthorName(resultSet.getString(index++));
		problemData.setAuthorEmail(resultSet.getString(index++));
		problemData.setAuthorWebsite(resultSet.getString(index++));
		problemData.setTimestampUtc(resultSet.getLong(index++));
		problemData.setLicense(ProblemLicense.fromOrdinal(resultSet.getInt(index++)));
	}

	protected void load(Change change, ResultSet resultSet, int index) throws SQLException {
//		change.setId(resultSet.getInt(index++));
		change.setEventId(resultSet.getInt(index++));
		change.setType(resultSet.getInt(index++));
		change.setStartRow(resultSet.getInt(index++));
		change.setEndRow(resultSet.getInt(index++));
		change.setStartColumn(resultSet.getInt(index++));
		change.setEndColumn(resultSet.getInt(index++));
		
		// Only one of the two text columns (text_short and text)
		// should be non-null.
		String shortText = resultSet.getString(index++);
		String longText = resultSet.getString(index++);
		
		String text = shortText != null ? shortText : longText;
		change.setText(text);
	}

	protected void load(Course course, ResultSet resultSet, int index) throws SQLException {
		course.setId(resultSet.getInt(index++));
		course.setName(resultSet.getString(index++));
		course.setTitle(resultSet.getString(index++));
		course.setUrl(resultSet.getString(index++));
		course.setTermId(resultSet.getInt(index++));
		course.setYear(resultSet.getInt(index++));
	}

	protected void load(Term term, ResultSet resultSet, int index) throws SQLException {
		term.setId(resultSet.getInt(index++));
		term.setName(resultSet.getString(index++));
		term.setSeq(resultSet.getInt(index++));
	}

	protected void load(TestCase testCase, ResultSet resultSet, int index) throws SQLException {
		testCase.setTestCaseId(resultSet.getInt(index++));
		testCase.setProblemId(resultSet.getInt(index++));
		testCase.setTestCaseName(resultSet.getString(index++));
		testCase.setInput(resultSet.getString(index++));
		testCase.setOutput(resultSet.getString(index++));
		testCase.setSecret(resultSet.getBoolean(index++));
	}
	
	protected void load(SubmissionReceipt submissionReceipt, ResultSet resultSet, int index) throws SQLException {
//		submissionReceipt.setId(resultSet.getInt(index++));
		submissionReceipt.setEventId(resultSet.getInt(index++));
		submissionReceipt.setLastEditEventId(resultSet.getInt(index++));
		submissionReceipt.setStatus(resultSet.getInt(index++));
		submissionReceipt.setNumTestsAttempted(submissionReceipt.getNumTestsAttempted());
		submissionReceipt.setNumTestsPassed(submissionReceipt.getNumTestsPassed());
	}
	
	protected void load(Event event, ResultSet resultSet, int index) throws SQLException {
		event.setId(resultSet.getInt(index++));
		event.setUserId(resultSet.getInt(index++));
		event.setProblemId(resultSet.getInt(index++));
		event.setType(resultSet.getInt(index++));
		event.setTimestamp(resultSet.getLong(index++));
	}
	
	protected void load(CourseRegistration reg, ResultSet resultSet, int index) throws SQLException {
		reg.setId(resultSet.getInt(index++));
		reg.setCourseId(resultSet.getInt(index++));
		reg.setUserId(resultSet.getInt(index++));
		reg.setRegistrationType(resultSet.getInt(index++));
		reg.setSection(resultSet.getInt(index++));
	}

	protected void storeNoId(Event event, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, event.getUserId());
		stmt.setInt(index++, event.getProblemId());
		stmt.setInt(index++, event.getType().ordinal());
		stmt.setLong(index++, event.getTimestamp());
	}

	protected void store(Change change, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, change.getEventId());
		stmt.setInt(index++, change.getType().ordinal());
		stmt.setInt(index++, change.getStartRow());
		stmt.setInt(index++, change.getEndRow());
		stmt.setInt(index++, change.getStartColumn());
		stmt.setInt(index++, change.getEndColumn());
		
		// Determine if text can be stored in text_short column,
		// or if it must be stored in the text (blob) column.
		String text = change.getText();
		
		String shortText = null;
		String longText = null;
		
		if (text.length() < Change.MAX_TEXT_LEN_IN_ROW) {
			shortText = text;
		} else {
			longText = text;
		}
		
		stmt.setString(index++, shortText);
		stmt.setString(index++, longText);
	}

	protected void store(SubmissionReceipt receipt, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, receipt.getEventId());
		stmt.setLong(index++, receipt.getLastEditEventId());
		stmt.setInt(index++, receipt.getStatus().ordinal());
		stmt.setInt(index++, receipt.getNumTestsAttempted());
		stmt.setInt(index++, receipt.getNumTestsPassed());
	}

	protected void storeNoId(TestResult testResult, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, testResult.getSubmissionReceiptEventId());
		stmt.setInt(index++, testResult.getOutcome().ordinal());
		stmt.setString(index++, testResult.getMessage());
		stmt.setString(index++, testResult.getStdout());
		stmt.setString(index++, testResult.getStderr());
	}
	
	protected int storeNoId(Problem problem, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, problem.getCourseId());
		stmt.setLong(index++, problem.getWhenAssigned());
		stmt.setLong(index++, problem.getWhenDue());
		stmt.setBoolean(index++, problem.isVisible());
		index = storeProblemData(problem, stmt, index);
		return index;
	}

	protected int storeProblemData(ProblemData problemData, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, problemData.getProblemType().ordinal());
		stmt.setString(index++, problemData.getTestname());
		stmt.setString(index++, problemData.getBriefDescription());
		stmt.setString(index++, problemData.getDescription());
		stmt.setString(index++, problemData.getSkeleton());
		stmt.setInt(index++, problemData.getSchemaVersion());
		stmt.setString(index++, problemData.getAuthorName());
		stmt.setString(index++, problemData.getAuthorEmail());
		stmt.setString(index++, problemData.getAuthorWebsite());
		stmt.setLong(index++, problemData.getTimestampUtc());
		stmt.setInt(index++, problemData.getLicense().ordinal());
		return index;
	}

	protected void storeNoId(TestCase testCase, PreparedStatement stmt, int index) throws SQLException {
		stmt.setInt(index++, testCase.getProblemId());
		stmt.setString(index++, testCase.getTestCaseName());
		stmt.setString(index++, testCase.getInput());
		stmt.setString(index++, testCase.getOutput());
		stmt.setBoolean(index++, testCase.isSecret());
	}

	protected Change getChangeAndEvent(ResultSet resultSet) throws SQLException {
		Change change = new Change();
		load(change, resultSet, 1);
		Event event = new Event();
		load(event, resultSet, Change.NUM_FIELDS + 1);
		change.setEvent(event);
		return change;
	}

	private SubmissionReceipt loadSubmissionReceiptAndEvent(ResultSet resultSet) throws SQLException {
		SubmissionReceipt submissionReceipt = new SubmissionReceipt();
		load(submissionReceipt, resultSet, 1);
		load(submissionReceipt.getEvent(), resultSet, SubmissionReceipt.NUM_FIELDS + 1);
		return submissionReceipt;
	}
}
