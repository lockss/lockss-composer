<!--

Copyright (c) 2000-2019 Board of Trustees of Leland Stanford Jr. University,
all rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

--> 
# LOCKSS Composer
Composer of LOCKSS REST services.

## Build and deployment
### Clone the repo
`git clone -b develop ssh://github.com/lockss/lockss-composer.git`

### Build the composed web service:
To compose the single service with the Configuration, Metadata Extraction,
Metadata Query and Poller services, in the home directory of this project, where
this `README.md` file resides, run `./compose allServices`.

The result of the build is a so-called "uber JAR" file which includes the
project code plus all its dependencies and which can be located via the symbolic
link at

`./laaws-composed/target/current-with-deps.jar`

### Run the composed web service:
Change to the `laaws-composed` directory and run the
[LOCKSS Development Scripts](https://github.com/lockss/laaws-dev-scripts)
project `bin/runservice -j` script.

The log is at `./laaws-composed/logs/app.log`.

The API is documented at <http://127.0.0.1:24690/swagger-ui.html>.

The status of the composed web service may be obtained at
<http://127.0.0.1:24690/status>.

The administration UI of the composed web service is at
<http://127.0.0.1:24691>.

