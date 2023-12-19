/**
 * This file was auto-generated by openapi-typescript.
 * Do not make direct changes to the file.
 */


export interface paths {
  "/scripts": {
    get: {
      parameters: {
        query?: {
          maxResults?: number;
          orderBy?: "modifiedAt" | "-modifiedAt";
        };
      };
      responses: {
        /** @description null */
        200: {
          content: {
            "application/json": {
              data?: components["schemas"]["Script"][];
              total?: number;
            };
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          "application/json": components["schemas"]["NewScript"];
        };
      };
      responses: {
        /** @description null */
        200: {
          content: {
            "application/json": components["schemas"]["Script"];
          };
        };
      };
    };
  };
  "/scripts/{id}": {
    put: {
      requestBody: {
        content: {
          "application/json": components["schemas"]["NewScript"];
        };
      };
      responses: {
        /** @description null */
        200: {
          content: {
            "application/json": components["schemas"]["Script"];
          };
        };
      };
    };
    delete: {
      parameters: {
        path: {
          id: string;
        };
      };
      responses: {
        /** @description null */
        204: {
          content: never;
        };
      };
    };
  };
  "/scripts/{id}/update_context": {
    put: {
      parameters: {
        path: {
          /** @description Script ID */
          id: string;
        };
      };
      requestBody: {
        content: {
          "application/json": string;
        };
      };
      responses: {
        /** @description null */
        200: {
          content: {
            "application/json": components["schemas"]["Script"];
          };
        };
      };
    };
    parameters: {
      path: {
        /** @description Script ID */
        id: string;
      };
    };
  };
}

export type webhooks = Record<string, never>;

export interface components {
  schemas: {
    NewScript: {
      content: string;
      context: string[];
      description: string;
      name: string;
      referencesList?: components["schemas"]["SourceVersionReference"][];
    };
    Script: {
      content: string;
      context: [string];
      createdAt: number;
      createdBy: components["schemas"]["User"];
      description: string;
      /** Format: uuid */
      id: string;
      modifiedAt: number;
      modifiedBy: components["schemas"]["User"];
      name: string;
      referencesList: components["schemas"]["SourceVersionReference"][];
    };
    User: {
      active: boolean;
      /** Format: email */
      email: string;
      firstName: string;
      /** Format: uuid */
      id: string;
      lastName: string;
      name: string;
      tag: string;
    };
    SourceVersionReference: {
      sourceName: string;
      reference: {
        /** @enum {string} */
        type: "BRANCH" | "TAG" | "COMMIT";
        value: string;
      };
    };
  };
  responses: never;
  parameters: never;
  requestBodies: never;
  headers: never;
  pathItems: never;
}

export type $defs = Record<string, never>;

export type external = Record<string, never>;

export type operations = Record<string, never>;
