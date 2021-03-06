package se.scalablesolutions.akka.spring.foo;

import se.scalablesolutions.akka.actor.UntypedActor;
import se.scalablesolutions.akka.actor.ActorRef;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.concurrent.CountDownLatch;


/**
 * test class
 */
public class PingActor extends UntypedActor implements ApplicationContextAware {

  private String stringFromVal;
  private String stringFromRef;
  public static String lastMessage = null;
  public static CountDownLatch latch = new CountDownLatch(1);


  private boolean gotApplicationContext = false;


  public void setApplicationContext(ApplicationContext context) {
    gotApplicationContext = true;
  }

  public boolean gotApplicationContext() {
    return gotApplicationContext;
  }

  public String getStringFromVal() {
    return stringFromVal;
  }

  public void setStringFromVal(String s) {
    stringFromVal = s;
  }

  public String getStringFromRef() {
    return stringFromRef;
  }

  public void setStringFromRef(String s) {
    stringFromRef = s;
  }

  private String longRunning() {
    try {
      Thread.sleep(6000);
    } catch (InterruptedException e) {
    }
    return "this took long";
  }

  public void onReceive(Object message) throws Exception {
    if (message instanceof String) {
      lastMessage = (String) message;
      if (message.equals("longRunning")) {
        ActorRef pongActor = UntypedActor.actorOf(PongActor.class).start();
        pongActor.sendRequestReply("longRunning", getContext());
      }
    latch.countDown();
    } else {
      throw new IllegalArgumentException("Unknown message: " + message);
    }
  }


}

