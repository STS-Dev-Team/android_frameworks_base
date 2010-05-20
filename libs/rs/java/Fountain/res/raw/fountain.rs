// Fountain test script
#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

static int newPart = 0;

float4 partColor;
rs_mesh partMesh;

typedef struct __attribute__((packed, aligned(4))) Point_s {
    float2 delta;
    rs_position2 pos;
    rs_color4u color;
} Point_t;
Point_t *point;

#pragma rs export_var(point, partColor, partMesh)

int root() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);
    float height = rsgGetHeight();
    rs_allocation alloc = rsGetAllocation(point);
    int size = rsAllocationGetDimX(alloc);

    Point_t * p = point;
    for (int ct=0; ct < size; ct++) {
        p->delta.y += 0.15f;
        p->pos += p->delta;
        if ((p->pos.y > height) && (p->delta.y > 0)) {
            p->delta.y *= -0.3f;
        }
        p++;
    }

    rsgUploadToBufferObject(alloc);
    rsgDrawSimpleMesh(partMesh);
    return 1;
}

void addParticles(int rate, int x, int y)
{
    rsDebug("partColor", partColor);
    rsDebug("partColor x", partColor.x);
    rsDebug("partColor y", partColor.y);
    rsDebug("partColor z", partColor.z);
    rsDebug("partColor w", partColor.w);

    float rMax = ((float)rate) * 0.005f;
    int size = rsAllocationGetDimX(rsGetAllocation(point));

    rs_color4u c = rsPackColorTo8888(partColor.x, partColor.y, partColor.z);
    Point_t * np = &point[newPart];

    float2 p = {x, y};
    while (rate--) {
        float angle = rsRand(3.14f * 2.f);
        float len = rsRand(rMax);
        np->delta.x = len * sin(angle);
        np->delta.y = len * cos(angle);
        np->pos = p;
        np->color = c;
        newPart++;
        np++;
        if (newPart >= size) {
            newPart = 0;
            np = &point[newPart];
        }
    }
}

