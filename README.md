# datomic-geodb

Sample code which illustrates how to transfer the open source data base for German geo locations [OpenGeoDB](http://opengeodb.org/wiki/OpenGeoDB) into [datomic](http://www.datomic.com).

## Installation

Add the following dependency to your `project.clj` file:

    [datomic-geodb "0.1.0-SNAPSHOT"]

## Usage
It follows a brief step by step introduction how to get a datomic database with OpenGeoDB up and running. For more information about Datomic refer to (http://www.datomic.com).

### Start a Datomic Transactor
The free version of Datomic provides a file system based transactor which serves at maximum two clients. This version can be obtained from (https://my.datomic.com/downloads/free). After having downloaded and extracted open a shell and set datomic-free-<version> as your working directory. Start the transactor by the following command line:

    bin/transactor config/samples/free-transactor-template.properties

Datomic is now shall be ready to be used with persistent memory.

### Transfer GeoDB data
The library provides the utility function `init-datomic-geobdb` which reads the [OpenGeoDB](http://opengeodb.org/wiki/OpenGeoDB) database in table form and converts it by the provided schema. Both files are provided within this repo under the subdirectory init:

* [schema.edn](https://github.com/linneman/datomic-geodb/tree/master/init/geo-db-schema.edn)
* [DE.tab.txt](https://github.com/linneman/datomic-geodb/tree/master/init/DE.tab.txt)

The following snippet illustrates how to include the given library functions, create and connect to the geodb database and invoke the initialization method:

```clojure
(ns hello-world.core
  (:require [datomic-geodb.core :refer :all])
  (:use [datomic.api :only [q db] :as d]
        [clojure.pprint]))

(def uri "datomic:free://localhost:4334/geodb")
(d/create-database uri)
(def conn (d/connect uri))

(init-datomic-geobdb "<path to datomic_geodb>/init/DE.tab.txt" "<path to datomic_geodb>/init/geo-db-schema.edn" geodb-conn)
```

### Accessing the GeoDB data
The aforementioned steps are required only once. Afterwords all you need to do is to connect to the created geodb database and retrieve data from it. The following snippet illustrates how to extract all suboridnated communities for the German state Saxony and how to find all communities in the vicinity of a given city.

```clojure
(ns hello-world.core
  (:require [datomic-geodb.core :refer :all])
  (:use [datomic.api :only [q db] :as d]
        [clojure.pprint]))

(def uri "datomic:free://localhost:4334/geodb")
(def conn (d/connect uri))

(find-subordinated-communities conn "Sachsen")
(find-in-vicinity-to-name conn "Dresden" 20)
```

## License

Copyright © 2015 Otto Linnemann

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
