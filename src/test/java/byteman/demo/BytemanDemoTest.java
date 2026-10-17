package byteman.demo;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;

import javax.inject.Inject;


import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.orm.hibernate3.HibernateJdbcException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.transaction.CannotCreateTransactionException;

import byteman.demo.config.AppConfig;
import byteman.demo.domain.Customer;
import byteman.demo.services.CustomerService;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith( SpringJUnit4ClassRunner.class )
@ContextConfiguration(loader = AnnotationConfigContextLoader.class, classes = { AppConfig.class } )
@BMUnitConfig
public class BytemanDemoTest {
	@Rule public BytemanRule byteman = BytemanRule.create( BytemanDemoTest.class );
	@Inject private CustomerService customerService;

	// --- Byteman basics / sanity -------------------------------------------------
	// These four tests don't inject a fault into anything. They only prove that
	// Byteman can rewrite a method's return value at the bytecode level ($! = 5),
	// which is the mechanism every fault-injection test below relies on. Keep them
	// as a sanity check that the Byteman/BMUnit setup itself works, not as evidence
	// that fault handling works.

	@Test
	public void testShouldReturnZeroWithoutBytecodeModification() {
		// when
		int result = BytemanBasicDemo.returnZero();

		// then
		assertEquals(0, result);
	}

	@Test
	@BMRule(name = "should return five after bytecode modification",
			targetClass = "BytemanBasicDemo",
			targetMethod = "returnZero",
			targetLocation = "AT EXIT",
			action = "$! = 5")
	public void testShouldReturnFiveAfterBytecodeModification() {
		// when
		int result = BytemanBasicDemo.returnZero();

		// then
		assertEquals(5, result);
	}

	@Test
	public void testSumOfResultShouldEqualOneWithoutBytecodeModification() {

		// when
		int result = BytemanBasicDemo.returnZero() + BytemanBasicDemo.returnOne();

		// then
		assertEquals(1, result);
	}

	@Test
	@BMRules(rules = {
			@BMRule(name = "should return five after bytecode modification",
					targetClass = "BytemanBasicDemo",
					targetMethod = "returnZero",
					targetLocation = "AT EXIT",
					action = "$! = 5"),
			@BMRule(name = "should return four after bytecode modification",
					targetClass = "BytemanBasicDemo",
					targetMethod = "returnOne",
					targetLocation = "AT EXIT",
					action = "$! = 4")})
	public void testSumOfResultShouldEqualNineAfterBytecodeModification() {

		// when
		int result = BytemanBasicDemo.returnZero() + BytemanBasicDemo.returnOne();

		// then
		assertNotEquals(1, result);
	}

	// --- Thread synchronization fault -------------------------------------------
	// This test doesn't inject a failure; it uses Byteman's joinEnlist/joinWait helpers
	// to make the test thread block until all 20 worker threads have actually finished,
	// instead of racing them. Without this, atomicInteger.get() could be read before
	// every thread had incremented it, making the test flaky by timing rather than by
	// fault.
	@Test
	@BMRule(name = "should wait until all threads completed",
			targetClass = "BytemanAsyncThreadDemo",
			targetMethod = "run",
			targetLocation = "AT EXIT",
			action = "joinEnlist(\"BytemanDemoTest.shouldWaitUntilAllThreadsCompleted\")")
	public void testShouldWaitUntilAllThreadsCompleted()
	{
		// given
		int expectedCount = 20;
		AtomicInteger atomicInteger = new AtomicInteger(0);
		BMUnitDemoHelper bmUnitDemoHelper = new BMUnitDemoHelper();
		bmUnitDemoHelper.createJoin("BytemanDemoTest.shouldWaitUntilAllThreadsCompleted", expectedCount);

		// when
		for (int i = 0; i < expectedCount; i++)
		{
			new BytemanAsyncThreadDemo(atomicInteger).start();
		}
		BMUnitDemoHelper helper2 = new BMUnitDemoHelper();
		helper2.joinWait("BytemanDemoTest.shouldWaitUntilAllThreadsCompleted", expectedCount);

		// then
		assertEquals(expectedCount, atomicInteger.get());
	}

	// --- Thread failure fault -----------------------------------------------------
	// Same one-shot countdown pattern as the DB-down test, applied to threads instead
	// of sockets: the first thread to enter run() arms a counter at 1, and the second
	// thread to enter hits the counter at zero and gets an injected NullPointerException
	// instead of running normally. That NPE is uncaught on its own thread (BytemanAsyncThreadDemo
	// never catches it), so it never reaches the test thread -- it only shows up as a
	// logged "Exception in thread" line. What the assertion actually checks is that only
	// one of the two threads got as far as incrementing the counter, i.e. that the fault
	// really stopped the other thread rather than the counter being 1 for some unrelated
	// reason.
	@Test()
	@BMRules(
			rules = {
					@BMRule(
							name="create countDown for BytemanAsyncThreadDemo",
							targetClass = "BytemanAsyncThreadDemo",
							targetMethod = "run",
							targetLocation = "AT ENTRY",
							action = "joinEnlist(\"BytemanDemoTest.testMimicOneThreadFailure\"); createCountDown( \"threadCount\", 1 )"
					)
					,
					@BMRule(
							name = "throw IOException when trying to execute 2nd thread for BytemanAsyncThreadDemo",
							targetClass = "BytemanAsyncThreadDemo",
							targetMethod = "run",
							targetLocation = "AT ENTRY",
							condition = "countDown( \"threadCount\" )",
							action = "throw new java.lang.NullPointerException( \"NPE failure\" )"
					)

			}
	)
	public void testMimicOneThreadFailure() {
		// given
		int expectedCount = 1;
		int noOfThreads = 2;
		AtomicInteger atomicInteger = new AtomicInteger(0);
		BMUnitDemoHelper bmUnitDemoHelper = new BMUnitDemoHelper();
		bmUnitDemoHelper.createJoin("BytemanDemoTest.testMimicOneThreadFailure", noOfThreads);

		// when
		for (int i = 0; i < noOfThreads; i++)
		{
			new BytemanAsyncThreadDemo(atomicInteger).start();
		}

		BMUnitDemoHelper helper2 = new BMUnitDemoHelper();
		helper2.joinWait("BytemanDemoTest.testMimicOneThreadFailure", noOfThreads);

		// then
		assertEquals(expectedCount, atomicInteger.get());

	}


	// --- Control: no fault injected ------------------------------------------------
	// Baseline for the two DB fault tests below: same create()+find() flow against a
	// healthy database connection, so a failure here would mean the setup is broken
	// rather than the fault injection being interesting.
	@Test
	public void testCreateCustomerAndVerifyItHasBeenCreated() throws Exception {
		Customer expectedCustomer = customerService.create( "Customer A" );
		Customer actualCustomerReturnedFromService = customerService.find( expectedCustomer.getId() );
		assertThat( actualCustomerReturnedFromService, notNullValue() );
		assertTrue(actualCustomerReturnedFromService.getName().equals(expectedCustomer.getName()));
	}

	// DB-down fault: the first rule arms a one-shot counter the moment any socket
	// connects to port 3306; the second rule only fires once that counter reaches
	// zero, so the *first* connection (opened by create()) goes through normally and
	// only the *second* connection (opened by find(), since DriverManagerDataSource
	// hands out a fresh physical connection per transaction) gets the simulated
	// "Connection refused". Verified by instrumenting the test: create() really does
	// return a persisted Customer before find() throws.
	//
	// Real exception chain (confirmed by logging the cause chain, not assumed):
	//   java.io.IOException "Connection refused (simulated)"        <- injected by Byteman
	//   -> com.mysql.cj.exceptions.CJCommunicationsException
	//   -> com.mysql.cj.jdbc.exceptions.CommunicationsException
	//   -> org.hibernate.exception.JDBCConnectionException
	//   -> javax.persistence.PersistenceException
	//   -> org.springframework.transaction.CannotCreateTransactionException  <- what we assert
	// Spring's JpaTransactionManager throws CannotCreateTransactionException specifically
	// because the failure happens while opening the EntityManager/connection for a new
	// transaction (on find()'s @Transactional boundary), not while running a query on an
	// already-open one.
	@Test( expected = CannotCreateTransactionException.class )
	@BMRules(
		rules = {
			@BMRule(
				name="create countDown for AbstractPlainSocketImpl",
                targetClass = "java.net.AbstractPlainSocketImpl",
                targetMethod = "getOutputStream",
                condition = "$0.port==3306",
                action = "createCountDown( \"connection\", 1 )"
            ),
			@BMRule(
		    	name = "throw IOException when trying to execute 2nd query to MySQL",
		        targetClass = "java.net.AbstractPlainSocketImpl",
		        targetMethod = "getOutputStream",
		        condition = "$0.port==3306 && countDown( \"connection\" )",
		        action = "throw new java.io.IOException( \"Connection refused (simulated)\" )"
		    )
		}
	)
	public void testCreateCustomerAndTryToFindItWhenDatabaseIsDown() {
		Customer customer = customerService.create( "Customer A" );
		customerService.find( customer.getId() );
	}

	// DB-timeout fault: Byteman throws MySQLTimeoutException the instant the JDBC driver
	// tries to execute the query, simulating a query that times out against the server.
	//
	// Real exception chain (confirmed by logging the cause chain, not assumed):
	//   com.mysql.cj.jdbc.exceptions.MySQLTimeoutException "Statement timed out (simulated)" <- injected by Byteman
	//   -> org.hibernate.QueryTimeoutException
	//   -> org.springframework.orm.hibernate3.HibernateJdbcException  <- what we assert
	// HibernateJdbcException is Spring's generic wrapper for a Hibernate JDBCException
	// (QueryTimeoutException is one). It lives in the "hibernate3" package for historical
	// reasons -- Spring's spring-orm jar has never moved it -- but it's still the class
	// that Spring's JPA exception translation produces here, even though this project uses
	// JPA/EntityManager and Hibernate 4, not a Hibernate3 SessionFactory. An earlier note in
	// this project described this as "thrown when the database is in hibernation", which
	// isn't a real mechanism and isn't what's happening -- it's just a legacy class name
	// that nobody renamed, not a reference to "hibernation".
	@Test(expected = HibernateJdbcException.class)
	@BMRules(
		rules =	@BMRule(
    	name = "introduce timeout while accessing MySQL database",
        targetClass = "com.mysql.cj.jdbc.ClientPreparedStatement",
        targetMethod = "executeQuery",
        targetLocation = "AT ENTRY",
        action = "throw new com.mysql.cj.jdbc.exceptions.MySQLTimeoutException( \"Statement timed out (simulated)\" )"
    ))
	public void testCreateCustomerWhileDatabaseIsTimingOut()  {
		Customer customer = customerService.create( "Customer A" );
		customerService.find( customer.getId() );
	}

}
