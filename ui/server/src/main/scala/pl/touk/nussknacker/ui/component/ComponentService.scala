package pl.touk.nussknacker.ui.component

import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.ProcessingTypeData
import pl.touk.nussknacker.engine.ProcessingTypeData.ProcessingType
import pl.touk.nussknacker.engine.api.component.{ComponentId, ComponentType, SingleComponentConfig}
import pl.touk.nussknacker.restmodel.component.ComponentListElement
import pl.touk.nussknacker.restmodel.definition.ComponentTemplate
import pl.touk.nussknacker.ui.config.ComponentsActionConfigExtractor
import pl.touk.nussknacker.ui.definition.UIProcessObjectsFactory
import pl.touk.nussknacker.ui.process.ConfigProcessCategoryService
import pl.touk.nussknacker.ui.process.ProcessCategoryService.Category
import pl.touk.nussknacker.ui.process.processingtypedata.ProcessingTypeDataProvider
import pl.touk.nussknacker.ui.process.subprocess.{SubprocessDetails, SubprocessRepository}
import pl.touk.nussknacker.ui.security.api.LoggedUser

trait ComponentService {
  def getComponentsList(user: LoggedUser): List[ComponentListElement]
}

class DefaultComponentService(processingTypeDataProvider: ProcessingTypeDataProvider[ProcessingTypeData],
                              subprocessRepository: SubprocessRepository,
                              categoryService: ConfigProcessCategoryService) extends ComponentService with LazyLogging {

  override def getComponentsList(user: LoggedUser): List[ComponentListElement] = {
    val subprocess = subprocessRepository.loadSubprocesses()

    val components = processingTypeDataProvider.all.flatMap{
      case (processingType, processingTypeData) =>
        extractComponentsFromProcessingType(processingTypeData, processingType, subprocess, user)
    }

    val filteredComponents = components.filter(component => component.categories.nonEmpty)
    val deduplicatedComponents = deduplication(filteredComponents)

    deduplicatedComponents
      .sortBy(ComponentListElement.sortMethod)
  }

  private def extractComponentsFromProcessingType(processingTypeData: ProcessingTypeData,
                                                  processingType: ProcessingType,
                                                  subprocesses: Set[SubprocessDetails],
                                                  user: LoggedUser) = {
    val userCategories = categoryService.getUserCategories(user)
    val processingTypeCategories = categoryService.getProcessingTypeCategories(processingType)
    val userProcessingTypeCategories = userCategories.intersect(processingTypeCategories)

    //When user hasn't access to model then is no sens to extract data
    userProcessingTypeCategories match {
      case Nil => Nil
      case _ => extractUserComponentsFromProcessingType(processingTypeData, processingType, subprocesses, userProcessingTypeCategories, user)
    }
  }

  private def extractUserComponentsFromProcessingType(processingTypeData: ProcessingTypeData,
                                                  processingType: ProcessingType,
                                                  subprocesses: Set[SubprocessDetails],
                                                  userProcessingTypeCategories: List[Category],
                                                  user: LoggedUser) = {
    val processingTypeSubprocesses = subprocesses.filter(sub => userProcessingTypeCategories.contains(sub.category))
    val componentsAction = ComponentsActionConfigExtractor.extract(processingTypeData.modelData.processConfig)

    /**
      * TODO: Right now we use UIProcessObjectsFactory for extract components data, because there is assigned logic
      * responsible for: hiding, mapping group name, etc.. We should move this logic to another place, because
      * UIProcessObjectsFactory does many other things, things that we don't need here..
      */
    val uiProcessObjects = UIProcessObjectsFactory.prepareUIProcessObjects(
      processingTypeData.modelData,
      processingTypeData.deploymentManager,
      user,
      processingTypeSubprocesses,
      isSubprocess = false, //It excludes fragment's components: input / output
      categoryService
    )

    //We do it here because base component's (filter, switch, etc..) aren't configured
    def getComponentConfig(component: ComponentTemplate): Option[SingleComponentConfig] =
      uiProcessObjects.componentsConfig.get(component.label)

    def getComponentIcon(component: ComponentTemplate): String =
      getComponentConfig(component)
        .flatMap(_.icon)
        .getOrElse(DefaultsComponentIcon.fromComponentType(component.`type`))

    def getComponentCategories(component: ComponentTemplate) =
      if (ComponentType.isBaseComponent(component.`type`)) //Base components are available in all categories
        categoryService.getUserCategories(user)
      else //Situation when component contains categories not assigned to model..
        component.categories.intersect(userProcessingTypeCategories)

    def createActions(componentId: String, componentName: String, componentType: ComponentType) =
      componentsAction
        .filter{ case (_, action) => action.types.isEmpty || action.types.contains(componentType) }
        .map{ case (id, action) =>
          ComponentAction(id, action.title, action.url, action.icon, componentId, componentName)
        }
        .toList
        .sortBy(_.id)

    uiProcessObjects
      .componentGroups
      .flatMap(group => group.components.map(com => {
        //TODO: It is work around for components duplication across multiple scenario types, until we figure how to do deduplication.
        val id = ComponentId(processingType, com.label, com.`type`)
        val actions = createActions(id, com.label, com.`type`)
        ComponentListElement(
          id = id,
          name = com.label,
          icon = getComponentIcon(com),
          componentType = com.`type`,
          componentGroupName = group.name,
          categories = getComponentCategories(com),
          actions = actions
        )
      }
    ))
  }

  /**
    * TODO: It is work around for components duplication across multiple scenario types, until we figure how to do deduplication.
    * We should figure how to properly merge many components to one, what about difference: name, icon, actions?
    */
  private def deduplication(components: Iterable[ComponentListElement]) = {
    def doDeduplication(id: String, components: Iterable[ComponentListElement]) = {
      def getElement[T](name: String, data: Iterable[T]) = data.toList.distinct match {
        case head :: Nil => head
        case list =>
          logger.warn(s"Multiple component $name detected for component id: $id.")
          list.head
      }

      val name = getElement("name", components.map(_.name))
      val componentGroupName = getElement("componentGroupName", components.map(_.componentGroupName))
      val componentType = getElement("componentType", components.map(_.componentType))

      val icon = components.map(_.icon).toList.distinct match {
        case head :: Nil => head
        case _ => DefaultsComponentIcon.fromComponentType(componentType)
      }

      val categories = components.flatMap(_.categories).toList.distinct.sorted
      val actions = components.flatMap(_.actions).toList.distinct.sortBy(_.id)

      ComponentListElement(id, name, icon, componentType, componentGroupName, categories, actions)
    }

    val groupedComponents = components.groupBy(_.id)
    groupedComponents
      .map { case (id, components) => components match {
        case head :: Nil => head
        case _ => doDeduplication(id, components)
      }}
      .toList
  }
}