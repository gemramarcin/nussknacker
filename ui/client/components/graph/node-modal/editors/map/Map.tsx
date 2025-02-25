import React, {useCallback, useEffect, useMemo, useState} from "react"
import {Field, TypedObjectTypingResult, VariableTypes} from "../../../../../types"
import {FieldsRow} from "../../subprocess-input-definition/FieldsRow"
import {Items} from "../../subprocess-input-definition/Items"
import {NodeRowFields} from "../../subprocess-input-definition/NodeRowFields"
import {Error, mandatoryValueValidator} from "../Validators"
import MapKey from "./MapKey"
import MapValue from "./MapValue"
import {isEqual} from "lodash"

export interface MapCommonProps {
  isMarked: (paths: string) => boolean,
  onChange: (path: string, newValue: unknown) => void,
  readOnly?: boolean,
  showValidation: boolean,
  variableTypes: VariableTypes,
  errors: Error[],
}

interface MapProps<F extends Field> extends MapCommonProps {
  fields: F[],
  label: string,
  namespace: string,
  addField: (namespace: string, field?: F) => void,
  removeField: (namespace: string, index: number) => void,
  expressionType?: TypedObjectTypingResult,
}

export type TypedField = Field & { typeInfo: string }

export function Map<F extends Field>(props: MapProps<F>): JSX.Element {
  const {
    label, onChange, addField, removeField, namespace, isMarked, readOnly, showValidation,
    errors, variableTypes, expressionType,
  } = props

  const appendTypeInfo = useCallback((expressionObj: F): F & { typeInfo: string } => {
    const fields = expressionType?.fields
    const typeInfo = fields ? fields[expressionObj.name]?.display : expressionType?.display
    return {...expressionObj, typeInfo: typeInfo}
  }, [expressionType?.display, expressionType?.fields])

  const [fields, setFields] = useState(props.fields)
  useEffect(() => {
    if (!isEqual(props.fields, fields)) {
      setFields(props.fields)
    }
  }, [props.fields, fields])

  const validators = useMemo(() => [mandatoryValueValidator], [])

  const Item = useCallback(
    ({index, item}: { index: number, item }) => {
      const path = `${namespace}[${index}]`
      return (
        <FieldsRow index={index}>
          <MapKey
            readOnly={readOnly}
            showValidation={showValidation}
            isMarked={isMarked(`${path}.name`)}
            onChange={value => onChange(`${path}.name`, value)}
            value={item.name}
            validators={validators}
            autofocus={false}
          />
          <MapValue
            readOnly={readOnly}
            showValidation={showValidation}
            isMarked={isMarked(`${path}.expression.expression`)}
            onChange={value => onChange(`${path}.expression.expression`, value)}
            validationLabelInfo={item.typeInfo}
            value={item.expression}
            errors={errors}
            variableTypes={variableTypes}
          />
        </FieldsRow>
      )
    },
    // "variableTypes" ignored for reason
    [isMarked, namespace, onChange, readOnly, showValidation, validators],
  )

  const items = useMemo(
    () => fields.map(appendTypeInfo).map((item, index) => ({item, el: <Item key={index} index={index} item={item}/>})),
    [Item, appendTypeInfo, fields],
  )

  return (
    <NodeRowFields
      label={label}
      path={namespace}
      onFieldAdd={addField}
      onFieldRemove={removeField}
      readOnly={readOnly}
    >
      <Items items={items}/>
    </NodeRowFields>
  )
}

export default Map
