import {get} from "lodash"
import {UnknownFunction} from "../../../types/common"
import EditableEditor from "./editors/EditableEditor"
import React, {useCallback} from "react"
import {ExpressionLang} from "./editors/expression/types"
import {PossibleValue} from "./editors/Validators"

export interface AdditionalPropertyConfig {
  editor: any,
  label: string,
  values: Array<PossibleValue>,
}

interface Props {
  showSwitch: boolean,
  showValidation: boolean,
  propertyName: string,
  propertyConfig: AdditionalPropertyConfig,
  propertyErrors: Array<any>,
  editedNode: any,
  onChange: UnknownFunction,
  renderFieldLabel: UnknownFunction,
  readOnly: boolean,
}

export default function AdditionalProperty(props: Props) {

  const {
    showSwitch, showValidation, propertyName, propertyConfig, propertyErrors, editedNode, onChange, renderFieldLabel,
    readOnly,
  } = props

  const values = propertyConfig.values?.map(value => ({expression: value, label: value}))
  const current = get(editedNode, `additionalFields.properties.${propertyName}`) || ""
  const expressionObj = {expression: current, value: current, language: ExpressionLang.String}

  const onValueChange = useCallback((newValue) => onChange(`additionalFields.properties.${propertyName}`, newValue), [onChange, propertyName])

  return (
    <EditableEditor
      param={propertyConfig}
      fieldName={propertyName}
      fieldLabel={propertyConfig.label || propertyName}
      onValueChange={onValueChange}
      expressionObj={expressionObj}
      renderFieldLabel={renderFieldLabel}
      values={values}
      readOnly={readOnly}
      key={propertyName}
      showSwitch={showSwitch}
      showValidation={showValidation}
      //AdditionalProperties do not use any variables
      variableTypes={{}}
      errors={propertyErrors}
    />
  )
}
