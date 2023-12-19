/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

export default {
  title: "Utilities/Margin",
};

const sizes = [
  "05",
  "1",
  "105",
  "2",
  "205",
  "3",
  "4",
  "405",
  "5",
  "505",
  "6",
  "7",
  "8",
  "9",
  "905",
  "10",
];

export const Default = () => {
  return (
    <>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 p-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.m-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 px-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.mx-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 py-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.my-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 pl-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.ml-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 pr-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.mr-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 pt-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.mt-{size}</code>
            </div>
          </div>
        ))}
      </div>
      <div>
        {sizes.map((size) => (
          <div
            key={size}
            className={`bg-neutral-50 m-4 pb-${size} rounded`}
            style={{ display: "inline-flex" }}
          >
            <div className={`bg-brand-300 rounded`}>
              <code>.mb-{size}</code>
            </div>
          </div>
        ))}
      </div>
    </>
  );
};

Default.storyName = "Margin";
