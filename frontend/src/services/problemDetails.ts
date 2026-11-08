/* RFC 9457 problem details. Every failure from the API arrives in this shape,
   so components branch on a stable identifier instead of matching on prose. */

   export const PROBLEM_BASE = "https://screenbook.dev/problems";

   export interface ProblemDetail {
     /* URI naming the problem kind, e.g. ".../problems/seats-unavailable" */
     type: string;
     title: string;
     status: number;
     detail?: string;
     instance?: string;
     /* Problems may carry extra members, such as seatId on seats unavailable. */
     [key: string]: unknown;
   }
   
   /* The cases the client knows how to react to. Anything else is "unknown" and
      gets generic handling. */
   export type ProblemSlug =
     | "seats-unavailable"
     | "hold-expired"
     | "hold-already-converted"
     | "not-found"
     | "validation-failed"
     | "idempotency-key-in-progress"
     | "idempotency-key-reused"
     | "invalid-credentials"
     | "email-taken"
     | "invalid-refresh-token"
     | "unknown";
   
   export class ApiError extends Error {
     readonly status: number;
     readonly problem: ProblemDetail;
   
     constructor(problem: ProblemDetail) {
       super(problem.detail ?? problem.title);
       this.name = "ApiError";
       this.status = problem.status;
       this.problem = problem;
     }
   
     /* Last segment of the type URI. The full URI can change host without the
        meaning changing, so the slug is what's worth switching on. */
     get slug(): ProblemSlug {
       const segment = this.problem.type.split("/").pop() ?? "";
       switch (segment) {
         case "seats-unavailable":
         case "hold-expired":
         case "hold-already-converted":
         case "not-found":
         case "validation-failed":
         case "idempotency-key-in-progress":
         case "idempotency-key-reused":
         case "invalid-credentials":
         case "email-taken":
         case "invalid-refresh-token":
           return segment;
         default:
           return "unknown";
       }
     }
   
     is(slug: ProblemSlug): boolean {
       return this.slug === slug;
     }
   }