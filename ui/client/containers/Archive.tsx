import classNames from "classnames"
import React from "react"
import {Glyphicon} from "react-bootstrap"
import {Td, Tr} from "reactable"
import Date from "../components/common/Date"
import "../stylesheets/processes.styl"
import styles from "../containers/processesTable.styl"
import {ShowItem} from "./editItem"
import {Page} from "./Page"
import {ProcessesTabData} from "./Processes"
import {Filterable, ProcessesList, RowsRenderer} from "./ProcessesList"
import tabStyles from "../components/tabs/processTabs.styl"
import {SearchItem} from "./TableFilters"

const ElementsRenderer: RowsRenderer = ({processes}) => processes.map(process => (
  <Tr className="row-hover" key={process.name}>
    <Td column="name">{process.name}</Td>
    <Td column="category">{process.processCategory}</Td>
    <Td column="subprocess" className="centered-column">
      <Glyphicon glyph={process.isSubprocess ? "ok" : "remove"}/>
    </Td>
    <Td
      column="modifyDate"
      className="centered-column"
      value={process.modificationDate}
    >
      <Date date={process.modificationDate}/>
    </Td>
    <Td column="view" className={classNames("edit-column", styles.iconOnly)}>
      <ShowItem process={process}/>
    </Td>
  </Tr>
))

const sortable = ["name", "category", "modifyDate"]
const filterable: Filterable = ["name", "processCategory"]
const columns = [
  {key: "name", label: "Process name"},
  {key: "category", label: "Category"},
  {key: "subprocess", label: "Subprocess"},
  {key: "modifyDate", label: "Last modification"},
  {key: "view", label: "View"},
]

function Archive() {
  return (
    <Page className={tabStyles.tabContentPage}>
      <ProcessesList
        defaultQuery={{isArchived: true}}
        searchItems={[SearchItem.categories, SearchItem.isSubprocess]}

        sortable={sortable}
        filterable={filterable}
        columns={columns}

        RowsRenderer={ElementsRenderer}
      />
    </Page>
  )
}

export const ArchiveTabData = {
  path: `${ProcessesTabData.path}/archived`,
  header: "Archive",
  Component: Archive,
}
