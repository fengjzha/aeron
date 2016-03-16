/*
 * Copyright 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "DriverConductorProxy.h"

using namespace aeron::driver;
using namespace aeron::driver::media;

void DriverConductorProxy::createPublicationImage(
    std::int32_t sessionId,
    std::int32_t streamId,
    std::int32_t initialTermId,
    std::int32_t activeTermId,
    std::int32_t termOffset,
    std::int32_t termLength,
    std::int32_t mtuLength,
    InetAddress& controlAddress,
    InetAddress& srcAddress,
    ReceiveChannelEndpoint& channelEndpoint)
{
}
