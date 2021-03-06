/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import se.scalablesolutions.akka.config.Supervision. {SuperviseTypedActor, FaultHandlingStrategy}

private[akka] trait TypedActorConfiguratorBase {
  def getExternalDependency[T](clazz: Class[T]): T

  def configure(restartStrategy: FaultHandlingStrategy, components: List[SuperviseTypedActor]): TypedActorConfiguratorBase

  def inject: TypedActorConfiguratorBase

  def supervise: TypedActorConfiguratorBase

  def reset

  def stop
}
